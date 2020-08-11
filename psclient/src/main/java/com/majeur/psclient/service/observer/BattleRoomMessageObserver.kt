package com.majeur.psclient.service.observer

import android.graphics.Color
import android.os.Looper
import com.majeur.psclient.io.BattleTextBuilder
import com.majeur.psclient.model.battle.*
import com.majeur.psclient.model.common.Colors
import com.majeur.psclient.model.pokemon.BasePokemon
import com.majeur.psclient.model.pokemon.BattlingPokemon
import com.majeur.psclient.service.ActionQueue
import com.majeur.psclient.service.ServerMessage
import com.majeur.psclient.service.ShowdownService
import com.majeur.psclient.util.*
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.roundToInt

class BattleRoomMessageObserver(service: ShowdownService)
    : RoomMessageObserver<BattleRoomMessageObserver.UiCallbacks>(service) {

    var gameType: GameType? = null
        private set

    var battleRunning = false
        private set

    var gen = 0

    private val battleTextBuilder = BattleTextBuilder(service)
    private val actionQueue = ActionQueue(Looper.getMainLooper())
    private var p1Username: String? = null
    private var p2Username: String? = null
    private val myUsername get() = service.getSharedData<String>("myusername")?.drop(1) ?: ""

    private var previewPokemonIndexes = IntArray(2)
    private var lastDecisionRequest: BattleDecisionRequest? = null
    private var trainerPokemons: Array<BattlingPokemon?> = emptyArray()
    private var foePokemons: Array<BattlingPokemon?> = emptyArray()
    private var activeWeather: String? = null
    private val activeFieldEffects = mutableListOf<String>()
    private var lastMove: String? = null
    var roomBattleType: BattleType = BattleType.LIVE

    init {
        battleTextBuilder.setPokemonIdFactory { rawString: String ->
            try {
                return@setPokemonIdFactory PokemonId(getPlayer(rawString), rawString)
            } catch (e: NullPointerException) {
                return@setPokemonIdFactory null
            } catch (e: StringIndexOutOfBoundsException) {
                return@setPokemonIdFactory null
            }
        }
    }

    public override fun onRoomInit() {
        super.onRoomInit()
        p1Username = null
        p2Username = null
        gameType = null
        actionQueue.clear()
        battleRunning = true
        previewPokemonIndexes[0] = 0
        previewPokemonIndexes[1] = 0
        lastDecisionRequest = null
        activeWeather = null
        activeFieldEffects.clear()
    }

    public override fun onRoomDeInit() {
        super.onRoomDeInit()
        p1Username = null
        p2Username = null
        gameType = null
        actionQueue.clear()
        battleRunning = false
        previewPokemonIndexes = IntArray(2)
        activeWeather = null
        activeFieldEffects.clear()
        actionQueue.setBattleType(BattleType.LIVE) // clear to default setting
    }

    private fun getPlayer(rawId: String) = Player.get(rawId, p1Username, p2Username, myUsername)

    private fun getPokemonId(rawId: String) = PokemonId(getPlayer(rawId), rawId)

    val foeUsername get() = Player.FOE.username(p1Username!!, p2Username!!, myUsername)

    val trainerUsername get() = Player.TRAINER.username(p1Username!!, p2Username!!, myUsername)

    val isUserPlaying get() = trainerUsername == myUsername

    fun reAskForRequest() = lastDecisionRequest?.let {
        onDecisionRequest(it)
    }

    fun getBattlingPokemon(id: PokemonId): BattlingPokemon? {
        val arr = (if (id.foe) foePokemons else trainerPokemons)
        return if (id.position >= 0 && id.position < arr.size) arr[id.position] else null
    }

    private fun getBattlingPokemon(player: Player, position: Int): BattlingPokemon? {
        val arr = (if (player == Player.FOE) foePokemons else trainerPokemons)
        return if (position >= 0 && position < arr.size) arr[position] else null
    }

    public override fun onMessage(message: ServerMessage) {
        super.onMessage(message)
        message.newArgsIteration()
        val upMessage = upgradeMessage(message)
        if (upMessage.command[0] == '-') handleMinorActionCommand(upMessage) else handleRegularCommand(upMessage)
    }

    private fun upgradeMessage(message: ServerMessage) = when (message.command) {
        // "-activate" -> upgradeActivate(message)
        else -> message
    }

    private fun handleRegularCommand(message: ServerMessage) = when (message.command) {
        "gen" -> gen = message.nextArg.toInt()
        "break" -> handleBreak()
        "move" -> handleMove(message)
        "switch" -> handleSwitch(message)
        "drag" -> handleDrag(message)
        "detailschange" -> handleDetailsChanged(message)
        "turn" -> handleTurn(message)
        "player" -> handlePlayer(message)
        "upkeep" -> { }
        "faint" -> handleFaint(message)
        "teamsize" -> handleTeamSize(message)
        "gametype" -> handleGameType(message)
        "tier" -> printMessage(message.nextArg.bold())
        "rated" -> printMessage("Rated battle".tag(Color.GRAY))
        "rule" -> printMessage(message.nextArg.italic())
        "clearpoke" -> {
            previewPokemonIndexes[0] = 0
            previewPokemonIndexes[1] = 0
            onPreviewStarted()
        }
        "poke" -> handlePreviewPokemon(message)
        "teampreview" -> actionQueue.enqueueAction {} // Used to trigger action looping in case nothing has been posted before
        "start" -> {
            printMessage(battleTextBuilder.start(p1Username, p2Username))
            onBattleStarted()
        }
        "request" -> handleRequest(message)
        "inactive" -> handleInactive(message, true)
        "inactiveoff" -> handleInactive(message, false)
        "win" -> handleWin(message, false)
        "tie" -> handleWin(message, true)
        "cant" -> handleCant(message)
        "swap" -> handleSwap(message)
        else -> Unit
    }

    private fun handleBreak() {
        actionQueue.enqueueMinorAction { onMarkBreak() }
    }

    // |move|p2a: Pinsir|Close Combat|p1a: Latias|[miss]
    // |move|p2a: Dialga|Flash Cannon|p1: Shiftry|[notarget]
    private fun handleMove(msg: ServerMessage) {
        val sourcePoke = getPokemonId(msg.nextArg)
        val moveName = msg.nextArg
        val targetPoke = if (msg.hasNextArg) getPokemonId(msg.nextArg) else null

        val shouldAnim = !msg.kwargs.keys.containsAll(listOf("still", "notarget", "miss"))
        val text = battleTextBuilder.move(sourcePoke, moveName, msg.kwargs["from"],
                msg.kwargs["of"], msg.kwargs["zMove"])

        // Major action's duration would be too long here
        actionQueue.enqueueMinorAction {
            lastMove = moveName
            onMove(sourcePoke, targetPoke, moveName, shouldAnim)
            displayMajorActionMessage(text)
        }
    }

    private fun handlePlayer(msg: ServerMessage) {
        val playerId = msg.nextArg
        if (!msg.hasNextArg) return
        val username = msg.nextArg
        if (playerId.contains("1")) p1Username = username else p2Username = username
        if (p1Username != null && p2Username != null)
            onPlayerInit(Player.TRAINER.username(p1Username!!, p2Username!!, myUsername),
                    Player.FOE.username(p1Username!!, p2Username!!, myUsername))
    }

    private fun handleFaint(msg: ServerMessage) {
        val pokemonId = getPokemonId(msg.nextArg)
        actionQueue.enqueueMajorAction {
            onFaint(pokemonId)
            displayMajorActionMessage(battleTextBuilder.faint(pokemonId))
        }
    }

    private fun handleTeamSize(msg: ServerMessage) {
        val player = getPlayer(msg.nextArg)
        val count = msg.nextArg.toInt()
        onTeamSize(player, count)
    }

    private fun handleGameType(msg: ServerMessage) {
        when (msg.nextArg.trim()) {
            "doubles" -> {
                gameType = GameType.DOUBLE
                trainerPokemons = arrayOfNulls(2)
                foePokemons = arrayOfNulls(2)
            }
            "rotation", "triples" -> {
                gameType = GameType.TRIPLE
                printErrorMessage("Triple battles aren't fully implemented yet. " +
                        "App crash is a matter of seconds from now!")
                trainerPokemons = arrayOfNulls(3)
                foePokemons = arrayOfNulls(3)
            }
            else -> {
                gameType = GameType.SINGLE
                trainerPokemons = arrayOfNulls(1)
                foePokemons = arrayOfNulls(1)
            }
        }
        lastDecisionRequest?.gameType = gameType!!
    }

    private fun handleSwitch(msg: ServerMessage) {
        val raw = msg.remainingArgsRaw
        val player = getPlayer(raw)
        val pokemon = BattlingPokemon(player, raw)
        val prevPoke = getBattlingPokemon(pokemon.id)
        val username = player.username(p1Username!!, p2Username!!, myUsername)
        val text1 = battleTextBuilder.switchOut(prevPoke, username, msg.kwargs["from"])
        val text2 = battleTextBuilder.switchIn(pokemon, username)
        actionQueue.enqueueMajorAction {
            if (pokemon.id.isInBattle) {
                if (lastMove?.toId() == "batonpass" || lastMove?.toId() == "zbatonpass") pokemon.copyVolatiles(prevPoke, false)
                (if (pokemon.foe) foePokemons else trainerPokemons)[pokemon.position] = pokemon
            }
            onSwitch(pokemon)
            text1?.let { displayMajorActionMessage(it) }
            displayMajorActionMessage(text2)
        }
    }

    private fun handleDrag(msg: ServerMessage) {
        val raw = msg.remainingArgsRaw
        val player = getPlayer(raw)
        val pokemon = BattlingPokemon(player, raw)
        val text = battleTextBuilder.drag(pokemon)
        actionQueue.enqueueMajorAction {
            onSwitch(pokemon)
            displayMajorActionMessage(text)
        }
    }

    private fun handleDetailsChanged(msg: ServerMessage) {
        val raw = msg.remainingArgsRaw
        val player = getPlayer(raw)
        val pokemon = BattlingPokemon(player, raw)
        msg.newArgsIteration()
        msg.nextArg
        val arg2 = msg.nextArgSafe
        val arg3 = msg.nextArgSafe
        val text = battleTextBuilder.pokemonChange(msg.command, pokemon.id, arg2, arg3,
                msg.kwargs["of"], msg.kwargs["from"])
        actionQueue.enqueueAction {
            getBattlingPokemon(pokemon.id)?.apply {
                species = pokemon.species
                baseSpecies = pokemon.baseSpecies
                forme = pokemon.forme
                spriteId = pokemon.spriteId
            }
            onDetailsChanged(pokemon)
            displayMajorActionMessage(text)
        }
    }

    private fun handleTurn(msg: ServerMessage) {
        val text = "\n" concat " — Turn ${msg.nextArg} — ".bold().big()
        actionQueue.enqueueTurnAction {
            // super prevents from queuing message print
            super@BattleRoomMessageObserver.printMessage(text)
        }
    }

    private fun handleRequest(msg: ServerMessage) {
        val rawJson = msg.remainingArgsRaw
        if (rawJson.isEmpty()) return
        try {
            val jsonObject = JSONObject(rawJson)
            val request = BattleDecisionRequest(jsonObject, gameType)
            lastDecisionRequest = request
            actionQueue.setLastAction { onDecisionRequest(request) }
        } catch (e: JSONException) {
            Timber.e(e, "Error while parsing request json")
            printErrorMessage("An error has occurred while receiving choices.")
        }
    }

    private fun handlePreviewPokemon(msg: ServerMessage) {
        val player = getPlayer(msg.nextArg)
        val curIndex = previewPokemonIndexes[if (player == Player.FOE) 1 else 0]++
        val species = msg.nextArg.split(",")[0]
        val pokemon = BasePokemon().also { it.species = species }
        val hasItem = msg.hasNextArg
        onAddPreviewPokemon(PokemonId(player, curIndex), pokemon, hasItem)
    }

    private fun handleInactive(msg: ServerMessage, on: Boolean) {
        onTimerEnabled(on)
        val text = msg.nextArg
        if (text.startsWith("Time left:")) return
        printInactiveText(text)
    }

    private fun handleWin(msg: ServerMessage, tie: Boolean) {
        val username = msg.nextArgSafe
        val text = if (tie) battleTextBuilder.tie(p1Username, p2Username) else battleTextBuilder.win(username)
        actionQueue.enqueueAction {
            battleRunning = false
            onBattleEnded(username.orEmpty())
            displayMajorActionMessage(text)
            actionQueue.setLastAction(null)
        }
    }

    private fun handleCant(msg: ServerMessage) {
        val pokemonId = getPokemonId(msg.nextArg)
        val reason = msg.nextArg
        val move = msg.nextArgSafe
        val text = battleTextBuilder.cant(pokemonId, reason, move, msg.kwargs["of"])
        actionQueue.enqueueMajorAction { displayMajorActionMessage(text) }
    }

    // |swap|p2a: Dugtrio|1|[from] move: Ally Switch
    private fun handleSwap(msg: ServerMessage) {
        val sourceId = getPokemonId(msg.nextArg)
        val sourceIndex = Utils.indexOf(getBattlingPokemon(sourceId),
                if (sourceId.foe) foePokemons else trainerPokemons)
        val with = if (msg.hasNextArg) msg.nextArg else "-1"
        val targetIndex: Int
        targetIndex = if (Utils.isInteger(with)) {
            with.toInt()
        } else { // Not tested, old showdown feature
            val otherId = PokemonId(getPlayer(with), with)
            Utils.indexOf(getBattlingPokemon(otherId),
                    if (otherId.foe) foePokemons else trainerPokemons)
        }
        if (targetIndex == sourceIndex || targetIndex < 0) return
        actionQueue.enqueueMajorAction {
            onSwap(sourceId, targetIndex)
            val targetPoke = getBattlingPokemon(sourceId.player, targetIndex)
            displayMajorActionMessage(battleTextBuilder.swap(sourceId, targetPoke?.id))
            Utils.swap(if (sourceId.foe) foePokemons else trainerPokemons, sourceIndex, targetIndex)
        }
    }

    private fun handleMinorActionCommand(message: ServerMessage) = when (message.command.removePrefix("-")) {
        "message" -> printMessage(message.remainingArgsRaw)
        "fail" -> handleFail(message)
        "miss" -> handleMiss(message)
        "damage" -> handleHealthChange(message, true)
        "heal" -> handleHealthChange(message, false)
        "status" -> handleStatus(message, false)
        "curestatus" -> handleStatus(message, true)
        "cureteam" -> handleCureTeam(message)
        "boost" -> handleStatChange(message, true)
        "unboost" -> handleStatChange(message, false)
        "setboost" -> handleSetBoost(message)
        "clearboost", "clearpositiveboost", "clearnegativeboost" -> handleClearBoost(message)
        "clearallboost" -> handleClearAllBoost(message)
        "invertboost" -> handleInvertBoost(message)
        "weather" -> handleWeather(message)
        "fieldactivate", "fieldstart" -> handleField(message, true)
        "fieldend" -> handleField(message, false)
        "activate" -> handleActivate(message)
        "sidestart" -> handleSide(message, true)
        "sideend" -> handleSide(message, false)
        "crit", "resisted", "supereffective" -> handleMoveEffect(message)
        "immune" -> handleImmune(message)
        "item" -> handleItem(message, true)
        "enditem" -> handleItem(message, false)
        "ability" -> handleAbility(message, true)
        "endability" -> handleAbility(message, false)
        "mega" -> handleMega(message, false)
        "primal" -> handleMega(message, true)
        "formechange", "transform" -> handleFormeChange(message)
        "hint" -> actionQueue.enqueueMinorAction { displayMinorActionMessage("(${message.nextArg})") }
        "center" -> {
        }
        "start" -> handleVolatileStatus(message, true)
        "end" -> handleVolatileStatus(message, false)
        "block" -> handleBlock(message)
        "ohko" -> handleOhko()
        "combine" -> handleCombine()
        "notarget" -> handleNoTarget()
        "prepare" -> handlePrepare(message)
        "zpower" -> handleZPower(message, false)
        "zbroken" -> handleZPower(message, true)
        "hitcount" -> handleHitCount(message)
        "sethp" -> handleSetHp(message)
        "singleturn", "singlemove" -> handleSingle(message)
        else -> Unit
    }


    private fun handleFail(msg: ServerMessage) {
        val pokemonId = getPokemonId(msg.nextArg)
        val effect = msg.nextArgSafe
        val stat = msg.nextArgSafe
        val text = battleTextBuilder.fail(pokemonId, effect, stat, msg.kwargs["from"],
                msg.kwargs["of"], msg.kwargs["msg"], msg.kwargs["heavy"], msg.kwargs["weak"],
                msg.kwargs["forme"])
        actionQueue.enqueueMinorAction {
            onDisplayBattleToast(pokemonId, "Failed", Colors.GRAY)
            displayMinorActionMessage(text)
        }
    }

    private fun handleMiss(msg: ServerMessage) {
        val pokemonId = getPokemonId(msg.nextArg)
        val targetRawId = msg.nextArgSafe
        val targetPokeId = if (targetRawId != null) PokemonId(getPlayer(targetRawId), targetRawId) else null
        val text = battleTextBuilder.miss(pokemonId, targetPokeId, msg.kwargs["from"],
                msg.kwargs["of"])
        actionQueue.enqueueMinorAction {
            onDisplayBattleToast(targetPokeId ?: pokemonId, "Missed", Colors.GRAY)
            displayMinorActionMessage(text)
        }
    }

    private fun handleHealthChange(msg: ServerMessage, damage: Boolean) {
        val id = getPokemonId(msg.nextArg)
        val rawCondition = msg.nextArg
        val condition = Condition(rawCondition)
        actionQueue.enqueueMinorAction {
            // Here we need to do text creation and percentage computation in the action queue to
            // prevent pkmn's condition to be updated too early (ex: damage then heal)
            val percentage = computePercentage(getBattlingPokemon(id)?.condition, condition)
            val text: CharSequence
            text = if (damage)
                battleTextBuilder.damage(id, percentage, msg.kwargs["from"], msg.kwargs["of"],
                        msg.kwargs["partiallytrapped"])
            else
                battleTextBuilder.heal(id, msg.kwargs["from"], msg.kwargs["of"], msg.kwargs["wisher"])

            getBattlingPokemon(id)?.condition = condition
            onHealthChanged(id, condition)
            displayMinorActionMessage(text)
            onDisplayBattleToast(id,
                    (if (damage) "-" else "+") + percentage,
                    if (damage) Colors.RED else Colors.GREEN)
        }
    }

    private fun computePercentage(old: Condition?, neW: Condition): String {
        return if (old != null) "${(100f * abs(neW.hp - old.hp) / old.maxHp).roundToInt()}%" else "[${neW.hp}/${neW.maxHp}]"
    }

    private fun handleStatus(msg: ServerMessage, cure: Boolean) {
        val id = getPokemonId(msg.nextArg)
        val status = msg.nextArg
        val text: CharSequence
        text = if (!cure) battleTextBuilder.status(id, status, msg.kwargs["from"], msg.kwargs["of"]) else
            battleTextBuilder.curestatus(id, status, msg.kwargs["from"], msg.kwargs["of"], msg.kwargs["thaw"])
        actionQueue.enqueueMinorAction {
            if (id.isInBattle) {
                getBattlingPokemon(id)!!.condition?.status = if (cure) null else status
                onStatusChanged(id, if (cure) null else status)
            }
            displayMinorActionMessage(text)
        }
    }

    private fun handleCureTeam(msg: ServerMessage) {
        val text = battleTextBuilder.cureTeam(msg.kwargs["from"])
        actionQueue.enqueueMinorAction { displayMinorActionMessage(text) }
    }

    private fun handleStatChange(msg: ServerMessage, boost: Boolean) {
        val id = getPokemonId(msg.nextArg)
        val stat = msg.nextArgSafe
        val amount = msg.nextArgSafe
        val amountValue = (amount?.toIntOrNull() ?: 0) * (if (boost) 1 else -1)
        val text = battleTextBuilder.boost(msg.command, id, stat, amount,
                msg.kwargs["from"], msg.kwargs["of"], msg.kwargs["multiple"], msg.kwargs["zeffect"])
        actionQueue.enqueueMinorAction {
            val statModifiers = getBattlingPokemon(id)!!.statModifiers
            statModifiers.inc(stat, amountValue)
            onStatChanged(id)
            displayMinorActionMessage(text)
        }
    }

    private fun handleSetBoost(msg: ServerMessage) {
        val id = getPokemonId(msg.nextArg)
        val stat = msg.nextArg
        val amount = msg.nextArg.toIntOrNull() ?: 0
        val text = battleTextBuilder.setboost(id, msg.kwargs["from"], msg.kwargs["of"])
        actionQueue.enqueueMinorAction {
            val statModifiers = getBattlingPokemon(id)!!.statModifiers
            statModifiers[stat] = amount
            onStatChanged(id)
            displayMinorActionMessage(text)
        }
    }

    private fun handleClearBoost(msg: ServerMessage) {
        val id = getPokemonId(msg.nextArg)
        val source = msg.nextArgSafe
        val text = battleTextBuilder.clearBoost(id, source, msg.kwargs["from"],
                msg.kwargs["of"], msg.kwargs["zeffect"])
        actionQueue.enqueueMinorAction {
            val statModifiers = getBattlingPokemon(id)!!.statModifiers
            if (msg.command.contains("positive")) statModifiers.clearPositive() else if (msg.command.contains("negative")) statModifiers.clearNegative() else statModifiers.clear()
            onStatChanged(id)
            displayMinorActionMessage(text)
        }
    }

    private fun handleClearAllBoost(msg: ServerMessage) {
        val text = battleTextBuilder.clearAllBoost(msg.kwargs["from"])
        actionQueue.enqueueMinorAction {
            for (pokemon in trainerPokemons + foePokemons) {
                pokemon?.let {
                    it.statModifiers.clear()
                    onStatChanged(it.id)
                }
            }
            displayMinorActionMessage(text)
        }
    }

    private fun handleInvertBoost(msg: ServerMessage) {
        val id = getPokemonId(msg.nextArg)
        val text = battleTextBuilder.invertBoost(id, msg.kwargs["from"], msg.kwargs["of"])
        actionQueue.enqueueMinorAction {
            getBattlingPokemon(id)?.let {
                it.statModifiers.invert()
                onStatChanged(it.id)
            }
            displayMinorActionMessage(text)
        }
    }

    private fun handleWeather(msg: ServerMessage) {
        val weather = msg.nextArg
        val text = battleTextBuilder.weather(weather, activeWeather,
                msg.kwargs["from"], msg.kwargs["of"], msg.kwargs["upkeep"])
        actionQueue.enqueueMinorAction {
            activeWeather = if ("none" == weather) null else weather
            val nextEffect = when {
                activeWeather != null -> weather
                activeFieldEffects.isNotEmpty() -> activeFieldEffects.last()
                else -> null
            }
            onFieldEffectChanged(nextEffect)
            displayMinorActionMessage(text)
        }
    }

    private fun handleField(msg: ServerMessage, start: Boolean) {
        val effect = msg.nextArg
        val fieldEffect = effect.substringAfter(":").toId()
        val text = if (start)
            battleTextBuilder.field(msg.command, effect, msg.kwargs["from"],
                    msg.kwargs["of"]) else battleTextBuilder.fieldend(effect)
        actionQueue.enqueueMinorAction {
            if (start) {
                activeFieldEffects.add(fieldEffect)
                if (activeWeather == null) {
                    onFieldEffectChanged(fieldEffect)
                }
            } else {
                activeFieldEffects.remove(fieldEffect)
                if (activeWeather == null) {
                    if (activeFieldEffects.isNotEmpty()) {
                        onFieldEffectChanged(activeFieldEffects.last())
                    } else {
                        onFieldEffectChanged(null)
                    }
                }
            }
            displayMinorActionMessage(text)
        }
    }

    private fun handleActivate(msg: ServerMessage) {
        val id = getPokemonId(msg.nextArg)
        val effect = msg.nextArgSafe
        val target = msg.nextArgSafe
        val text = battleTextBuilder.activate(id, effect, target, msg.kwargs["of"],
                msg.kwargs["ability"], msg.kwargs["ability2"], msg.kwargs["move"], msg.kwargs["number"],
                msg.kwargs["item"], msg.kwargs["name"])
        actionQueue.enqueueMinorAction { displayMinorActionMessage(text) }
    }

    private fun handleSide(msg: ServerMessage, start: Boolean) {
        val player = getPlayer(msg.nextArg)
        val effect = msg.nextArgSafe
        val sideName = effect?.substringAfter(":") ?: ""
        val text: CharSequence
        text = if (start) battleTextBuilder.sidestart(player, effect) else battleTextBuilder.sideend(player, effect)
        actionQueue.enqueueMinorAction {
            onSideChanged(player, sideName, start)
            displayMinorActionMessage(text)
        }
    }

    private fun handleMoveEffect(msg: ServerMessage) {
        val pokemonId = getPokemonId(msg.nextArg)
        val text = battleTextBuilder.moveeffect(msg.command,
                pokemonId, msg.kwargs["spread"])
        val toastText = when (msg.command) {
            "-crit" -> "Critical"
            "-resisted" -> "Resisted" // Gray
            "-supereffective" -> "Super-effective"
            else -> "???${msg.command}???"
        }
        val color = when (msg.command) {
            "-resisted" -> Colors.GRAY
            else -> Colors.RED
        }
        actionQueue.enqueueMinorAction {
            displayMinorActionMessage(text)
            onDisplayBattleToast(pokemonId, toastText, color)
        }
    }

    private fun handleImmune(msg: ServerMessage) {
        val pokemonId = getPokemonId(msg.nextArg)
        val text = battleTextBuilder.immune(pokemonId, msg.kwargs["from"],
                msg.kwargs["of"], msg.kwargs["ohko"])
        actionQueue.enqueueMinorAction {
            displayMinorActionMessage(text)
            onDisplayBattleToast(pokemonId, "Immune", Colors.GRAY)
        }
    }

    private fun handleItem(msg: ServerMessage, start: Boolean) {
        val id = getPokemonId(msg.nextArg)
        val item = msg.nextArgSafe
        val text: CharSequence
        text = if (start) battleTextBuilder.item(id, item, msg.kwargs["from"], msg.kwargs["of"]) else battleTextBuilder.enditem(id, item, msg.kwargs["from"], msg.kwargs["of"],
                msg.kwargs["eat"], msg.kwargs["move"], msg.kwargs["weaken"])
        actionQueue.enqueueMinorAction {
            // TODO Maybe show a toast ?
            displayMinorActionMessage(text)
        }
    }

    private fun handleAbility(msg: ServerMessage, start: Boolean) {
        val pokemonId = getPokemonId(msg.nextArg)
        val ability = msg.nextArg
        val oldAbility = msg.nextArgSafe
        val arg4 = msg.nextArgSafe
        val text: CharSequence
        text = if (start) battleTextBuilder.ability(pokemonId, ability, oldAbility, arg4,
                msg.kwargs["from"], msg.kwargs["of"], msg.kwargs["fail"]) else battleTextBuilder.endability(pokemonId, ability, msg.kwargs["from"],
                msg.kwargs["of"])
        actionQueue.enqueueMinorAction {
            displayMinorActionMessage(text)
            if (start) onDisplayBattleToast(pokemonId, ability, Colors.BLUE)
        }
    }

    private fun handleMega(msg: ServerMessage, primal: Boolean) {
        val pokemonId = getPokemonId(msg.nextArg)
        val species = msg.nextArgSafe
        val item = msg.nextArgSafe
        val text = battleTextBuilder.mega(pokemonId, species, item, primal)
        actionQueue.enqueueMinorAction { displayMinorActionMessage(text) }
    }

    private fun handleFormeChange(msg: ServerMessage) {
        // TODO: If a pokemon swap occurs in the action queue after this,
        //  we might display an incorrect target when calling onDetailsChanged().
        //  This should be called on the action queue callback.
        val pokemonId = getPokemonId(msg.nextArg)
        val arg2 = msg.nextArgSafe
        val arg3 = msg.nextArgSafe
        val text = battleTextBuilder.pokemonChange(msg.command,
                pokemonId, arg2, arg3, msg.kwargs["of"], msg.kwargs["from"])
        actionQueue.enqueueMinorAction {
            displayMinorActionMessage(text)
            if (msg.command.contains("transform") && arg2 != null) {
                val targetId = PokemonId(getPlayer(arg2), arg2)
                if (pokemonId == targetId) return@enqueueMinorAction
                val pokemon = getBattlingPokemon(pokemonId)
                val tpokemon = getBattlingPokemon(targetId)
                pokemon?.transformSpecies = tpokemon?.spriteId
                onDetailsChanged(pokemon!!)
                for (vStatus in pokemon.volatiles) onVolatileStatusChanged(pokemonId, vStatus, false)
                for (vStatus in tpokemon!!.volatiles) onVolatileStatusChanged(pokemonId, vStatus, true)
                onVolatileStatusChanged(pokemonId, "transform", true)
                pokemon.statModifiers.set(tpokemon.statModifiers)
                onStatChanged(pokemonId)
            } else if (msg.command.contains("formechange") && arg2 != null) {
                val pokemon = getBattlingPokemon(pokemonId)
                pokemon!!.spriteId = BasePokemon().also { it.species = arg2 }.spriteId
                onDetailsChanged(pokemon)
            }
        }
    }

    private fun handleVolatileStatus(msg: ServerMessage, start: Boolean) {
        val id = getPokemonId(msg.nextArg)
        val effect = msg.nextArg
        val arg3 = msg.nextArgSafe
        val silent = msg.kwargs.containsKey("silent")
        val text: CharSequence
        text = if (start) battleTextBuilder.start(id, effect, arg3, msg.kwargs["from"], msg.kwargs["of"],
                msg.kwargs["already"], msg.kwargs["fatigue"], msg.kwargs["zeffect"],
                msg.kwargs["damage"], msg.kwargs["block"], msg.kwargs["upkeep"]) else battleTextBuilder.end(id, effect, msg.kwargs["from"], msg.kwargs["of"])
        actionQueue.enqueueMinorAction {
            var effectId = effect.substringAfter(":").toId()
            onVolatileStatusChanged(id, effectId, start)
            val pokemon = getBattlingPokemon(id)
            if (pokemon != null) {
                if (effectId.startsWith("stockpile")) effectId = "stockpile"
                if (effectId.startsWith("perish")) effectId = "perish"
                if (start && effectId == "smackdown") {
                    pokemon.volatiles.remove("magnetrise")
                    pokemon.volatiles.remove("telekinesis")
                }
                if (start) pokemon.volatiles.add(effectId) else pokemon.volatiles.remove(effectId)
            }
            if (!silent) displayMinorActionMessage(text)
        }
    }

    private fun handleBlock(msg: ServerMessage) {
        val id = getPokemonId(msg.nextArg)
        val effect = msg.nextArgSafe
        val move = msg.nextArgSafe
        val attacker = msg.nextArgSafe
        val text = battleTextBuilder.block(id, effect, move, attacker,
                msg.kwargs["from"], msg.kwargs["of"])
        actionQueue.enqueueMinorAction { displayMinorActionMessage(text) }
    }

    private fun handleOhko() {
        val text = battleTextBuilder.ohko()
        actionQueue.enqueueMinorAction { displayMinorActionMessage(text) }
    }

    private fun handleCombine() {
        val text = battleTextBuilder.combine()
        actionQueue.enqueueMinorAction { displayMinorActionMessage(text) }
    }

    private fun handleNoTarget() {
        val text = battleTextBuilder.notarget()
        actionQueue.enqueueMinorAction { displayMinorActionMessage(text) }
    }

    private fun handlePrepare(msg: ServerMessage) {
        val id = getPokemonId(msg.nextArg)
        val effect = msg.nextArgSafe
        val target = msg.nextArgSafe
        val text = battleTextBuilder.prepare(id, effect, target)
        actionQueue.enqueueMinorAction { displayMinorActionMessage(text) }
    }

    private fun handleZPower(msg: ServerMessage, broken: Boolean) {
        val id = getPokemonId(msg.nextArg)
        val text = if (broken) battleTextBuilder.zbroken(id) else battleTextBuilder.zpower(id)
        actionQueue.enqueueMinorAction { displayMinorActionMessage(text) } // Todo Animate callback
    }

    private fun handleHitCount(msg: ServerMessage) {
        if (msg.hasNextArg) msg.nextArg
        val count = msg.nextArgSafe
        val text = battleTextBuilder.hitcount(count)
        actionQueue.enqueueMinorAction { displayMinorActionMessage(text) }
    }

    private fun handleSetHp(msg: ServerMessage) {
        val id = getPokemonId(msg.nextArg)
        val rawCondition = msg.nextArg
        val condition = Condition(rawCondition)
        val text = battleTextBuilder.sethp(msg.kwargs["from"])
        actionQueue.enqueueMinorAction {
            if (id.isInBattle) onHealthChanged(id, condition)
            displayMinorActionMessage(text)
        }
    }

    private fun handleSingle(msg: ServerMessage) {
        val id = getPokemonId(msg.nextArg)
        val effect = msg.nextArgSafe
        val text = battleTextBuilder.single(id, effect, msg.kwargs["from"],
                msg.kwargs["of"])
        actionQueue.enqueueMinorAction { displayMinorActionMessage(text) }
    }

    // This should be called only from action queue runnables
    private fun displayMajorActionMessage(text: CharSequence?) {
        if (text == null) return
        // Calling super to prevent queuing
        super.printMessage(text)
        onPrintBattleMessage(text)
    }

    // This should be called only from action queue runnables
    private fun displayMinorActionMessage(text: CharSequence?) {
        if (text == null) return
        // Calling super to prevent queuing
        super.printMessage(text.small())
        onPrintBattleMessage(text.small())
    }

    private fun printInactiveText(text: String?) {
        if (text == null) return
        printMessage(text.small().italic().color(-0x750000))
    }

    override fun printMessage(text: CharSequence) {
        // Include eventual message prints from super class in the action queue.
        actionQueue.enqueueAction { super@BattleRoomMessageObserver.printMessage(text) }
    }

    override fun printHtml(html: String) {
        // Include eventual html prints from super class in the action queue.
        actionQueue.enqueueAction { super@BattleRoomMessageObserver.printHtml(html) }
    }


    fun handleReplayAction(replayAction: ReplayAction) {
        if (this.roomBattleType != BattleType.REPLAY) return

        when(replayAction) {
            ReplayAction.PLAY -> {
                actionQueue.startLoop()
                uiCallbacks?.goToLatest()
            }
            ReplayAction.PAUSE -> actionQueue.stopLoop()
            ReplayAction.NEXT_TURN -> {
                actionQueue.skipToNext()
                uiCallbacks?.goToLatest()
            }
            ReplayAction.PREV_TURN -> {
                TODO("ReplayAction.PREV_TURN is not yet implemented")
            }
            ReplayAction.CLOSE_REPLAY -> {
                this.roomBattleType = BattleType.LIVE
                onRoomDeInit()
            }
        }
    }

    fun onSetBattleType(type: BattleType) {
        this.roomBattleType = type
        actionQueue.setBattleType(type)
        uiCallbacks?.onRoomBattleTypeChanged(type)
    }

    private fun onMarkBreak() = uiCallbacks?.onMarkBreak()
    private fun onPlayerInit(playerUsername: String, foeUsername: String) = uiCallbacks?.onPlayerInit(playerUsername, foeUsername)
    private fun onFaint(id: PokemonId) = uiCallbacks?.onFaint(id)
    private fun onTeamSize(player: Player, size: Int) = uiCallbacks?.onTeamSize(player, size)
    private fun onBattleStarted() = uiCallbacks?.onBattleStarted()
    private fun onBattleEnded(winner: String) = uiCallbacks?.onBattleEnded(winner)
    private fun onTimerEnabled(enabled: Boolean) = uiCallbacks?.onTimerEnabled(enabled)
    private fun onPreviewStarted() = uiCallbacks?.onPreviewStarted()
    private fun onAddPreviewPokemon(id: PokemonId, pokemon: BasePokemon, hasItem: Boolean) = uiCallbacks?.onAddPreviewPokemon(id, pokemon, hasItem)
    private fun onSwitch(newPokemon: BattlingPokemon) = uiCallbacks?.onSwitch(newPokemon)
    private fun onDetailsChanged(newPokemon: BattlingPokemon) = uiCallbacks?.onDetailsChanged(newPokemon)
    private fun onMove(sourceId: PokemonId, targetId: PokemonId?, moveName: String, shouldAnim: Boolean) = uiCallbacks?.onMove(sourceId, targetId, moveName, shouldAnim)
    private fun onSwap(id: PokemonId, targetIndex: Int) = uiCallbacks?.onSwap(id, targetIndex)
    private fun onDecisionRequest(request: BattleDecisionRequest) = uiCallbacks?.onDecisionRequest(request)
    private fun onHealthChanged(id: PokemonId, condition: Condition) = uiCallbacks?.onHealthChanged(id, condition)
    private fun onStatusChanged(id: PokemonId, status: String?) = uiCallbacks?.onStatusChanged(id, status)
    private fun onStatChanged(id: PokemonId) = uiCallbacks?.onStatChanged(id)
    private fun onDisplayBattleToast(id: PokemonId, text: String, color: Int) = uiCallbacks?.onDisplayBattleToast(id, text, color)
    private fun onFieldEffectChanged(weather: String?) = uiCallbacks?.onFieldEffectChanged(weather)
    private fun onSideChanged(player: Player, side: String, start: Boolean) = uiCallbacks?.onSideChanged(player, side, start)
    private fun onVolatileStatusChanged(id: PokemonId, vStatus: String, start: Boolean) = uiCallbacks?.onVolatileStatusChanged(id, vStatus, start)
    private fun onPrintBattleMessage(message: CharSequence) = uiCallbacks?.onPrintBattleMessage(message)

    interface UiCallbacks : RoomMessageObserver.UiCallbacks {
        fun onMarkBreak()
        fun onPlayerInit(playerUsername: String, foeUsername: String)
        fun onFaint(id: PokemonId)
        fun onTeamSize(player: Player, size: Int)
        fun onBattleStarted()
        fun onBattleEnded(winner: String)
        fun onTimerEnabled(enabled: Boolean)
        fun onPreviewStarted()
        fun onAddPreviewPokemon(id: PokemonId, pokemon: BasePokemon, hasItem: Boolean)
        fun onSwitch(newPokemon: BattlingPokemon)
        fun onDetailsChanged(newPokemon: BattlingPokemon)
        fun onMove(sourceId: PokemonId, targetId: PokemonId?, moveName: String, shouldAnim: Boolean)
        fun onSwap(id: PokemonId, targetIndex: Int)
        fun onDecisionRequest(request: BattleDecisionRequest)
        fun onHealthChanged(id: PokemonId, condition: Condition)
        fun onStatusChanged(id: PokemonId, status: String?)
        fun onStatChanged(id: PokemonId)
        fun onDisplayBattleToast(id: PokemonId, text: String, color: Int)
        fun onFieldEffectChanged(weather: String?)
        fun onSideChanged(player: Player, side: String, start: Boolean)
        fun onVolatileStatusChanged(id: PokemonId, vStatus: String, start: Boolean)
        fun onPrintBattleMessage(message: CharSequence)
        fun onRoomBattleTypeChanged(type: BattleType)
        fun goToLatest()
    }

    enum class BattleType {
        LIVE,
        REPLAY
        ;

        val isReplay get() = this == REPLAY
    }

    enum class ReplayAction {
        PLAY,
        PAUSE,
        NEXT_TURN,
        PREV_TURN,
        CLOSE_REPLAY
    }

    private fun upgradeActivate(message: ServerMessage): ServerMessage {
        val args = message.args
        val kwArgs = message.kwargs.toMutableMap()

        if (kwArgs.keys.containsOne(listOf("item", "move", "number", "ability"))) return message
        val pokemon = args[0]; val effect = args.getOrNull(1)
        val arg3 = args.getOrNull(2); val arg4 = args.getOrNull(3)
        val target = kwArgs["of"]
        val id = battleTextBuilder.effect(effect)

        if (kwArgs["block"] != null)
            return message.upgrade("-fail", listOf(pokemon))

        if (id == "wonderguard")
            return message.upgrade("-immune", listOf(pokemon), mapOf("from" to "ability:Wonder Guard"))

        if (id == "beatup" && kwArgs["of"] != null)
            return message.upgrade(kwargs = mapOf("name" to kwArgs["of"]!!))

        if (listOf("ingrain", "quickguard", "wideguard", "craftyshield", "matblock", "protect", "mist", "safeguard",
                        "electricterrain", "mistyterrain", "psychicterrain", "telepathy", "stickyhold", "suctioncups", "aromaveil",
                        "flowerveil", "sweetveil", "disguise", "safetygoggles", "protectivepads").contains(id)) {
            if (target != null) return message.upgrade("-block", listOfNotNull(target, effect, arg3),
                    kwArgs.plus("of" to pokemon))
            return message.upgrade("-block", listOfNotNull(target, effect, arg3))
        }

        if (id == "charge")
            return message.upgrade("-singlemove", listOfNotNull(pokemon, effect),
                    if (target != null) mapOf("of" to target) else emptyMap())

        if (listOf("bind", "wrap", "clamp", "whirlpool", "firespin", "magmastorm", "sandtomb",
                        "infestation", "trapped").contains(id))
            return message.upgrade("-start", listOfNotNull(pokemon, effect),
                    if (target != null) mapOf("of" to target) else emptyMap())

        if (id == "fairylock")
            return message.upgrade("-fieldactivate", listOfNotNull(effect), emptyMap())

        if (id == "symbiosis" || id == "poltergeist") {
            if (arg3 != null) kwArgs["item"] = arg3
        } else if (id === "magnitude") {
            if (arg3 != null) kwArgs["number"] = arg3
        } else if (id === "skillswap" || id === "mummy" || id === "wanderingspirit") {
            if (arg3 != null) kwArgs["ability"] = arg3
            if (arg4 != null) kwArgs["ability2"] = arg4
        } else if (listOf("spite", "grudge", "forewarn", "sketch", "leppaberry", "mysteryberry").contains(id)) {
            if (arg3 != null) kwArgs["move"] = arg3
            if (arg4 != null) kwArgs["number"] = arg4
        }
        return message.upgrade(args = listOfNotNull(pokemon, effect, target), kwargs = kwArgs)
    }
}