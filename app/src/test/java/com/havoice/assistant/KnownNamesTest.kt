package com.havoice.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnownNamesTest {
    private val today = 20_000L

    private fun talk(names: List<KnownName>, name: String, amount: Double, day: Long = today) =
        KnownNames.observe(names, mapOf(name to amount), day)

    private fun inUse(names: List<KnownName>, day: Long = today) = KnownNames.inUse(names, day).map { it.text }

    // Finding names in what was typed and replied.

    @Test
    fun findsNamesInAReply() {
        val reply = "Logged one serving of Orzo Pasta, 156 calories, and one serving of Field & Fern tuna for lunch."
        assertEquals(listOf("Orzo Pasta", "Field & Fern"), KnownNames.namesIn(reply))
    }

    @Test
    fun firstWordOfASentenceIsOnlyANameWhenAlreadyKnown() {
        val reply = "Logged it. Marlon's list now has a new backpack."
        assertEquals(emptyList<String>(), KnownNames.namesIn(reply))
        assertEquals(listOf("Marlon"), KnownNames.namesIn(reply) { it == "Marlon" })
    }

    @Test
    fun possessiveEndingIsNotPartOfTheName() {
        assertEquals(listOf("Harlow"), KnownNames.namesIn("I don't have Harlow's soup in the database."))
        assertEquals(listOf("Porter's Pick"), KnownNames.namesIn("That was the Porter's Pick shake."))
    }

    @Test
    fun aFullProductLabelKeepsItsBrand() {
        val reply = "I logged one serving of Field & Fern Sustainably Caught Chunk Light Tuna in Water."
        assertEquals(listOf("Field & Fern", "Water"), KnownNames.namesIn(reply))
        val shake = "I logged one Porter's Pick Strawberry High Protein Nutritional Shake for lunch."
        assertEquals(listOf("Porter's Pick"), KnownNames.namesIn(shake))
    }

    @Test
    fun daysMonthsAndIAreNotNames() {
        val reply = "It counts from tomorrow, November 18th, and I'm sure Friday works. The TV is off."
        assertEquals(emptyList<String>(), KnownNames.namesIn(reply))
    }

    @Test
    fun aNameTheRecognizerAlreadyWroteIsNoEvidence() {
        val known = { w: String -> w == "Marlon" }
        val heardRight = KnownNames.sightings(null, "Marlon wants a kite for the weekend.", "Got it, a kite is on the list for Marlon.", known)
        assertEquals(emptyMap<String, Double>(), heardRight)
        val heardWrong = KnownNames.sightings(null, "Marlin wants a new backpack.", "Got it, I added a new backpack for Marlon.", known)
        assertEquals(mapOf("Marlon" to KnownNames.FROM_REPLY), heardWrong)
    }

    @Test
    fun aTypedNameCountsMoreThanOneInAReply() {
        val seen = KnownNames.sightings("add a book for Rosalind to the list", null, "Added a book for Rosalind.", { false })
        assertEquals(mapOf("Rosalind" to KnownNames.FROM_TYPED), seen)
    }

    // What counts, and for how much.

    @Test
    fun aNameFromOneConversationIsNotUsedYet() {
        assertEquals(emptyList<String>(), inUse(talk(emptyList(), "Belmonte", KnownNames.FROM_REPLY)))
    }

    @Test
    fun everyRequestCountsEvenOnTheSameDay() {
        var names = talk(emptyList(), "Belmonte", KnownNames.FROM_REPLY)
        names = talk(names, "belmonte", KnownNames.FROM_REPLY)
        assertEquals(listOf("Belmonte"), inUse(names))
        repeat(98) { names = talk(names, "Belmonte", KnownNames.FROM_REPLY) }
        assertEquals(KnownNames.MAX_USES, names.single().uses, 0.0001)
    }

    @Test
    fun aTypedNameIsUsedAtOnce() {
        assertEquals(listOf("Lunavista"), inUse(talk(emptyList(), "Lunavista", KnownNames.FROM_TYPED)))
    }

    @Test
    fun aListedNameIsUsedFromTheStart() {
        val names = KnownNames.withSource(emptyList(), NameSource.HOME, listOf("Living Room Speaker", "Sink Light"), today)
        assertEquals(listOf("Living Room Speaker", "Sink Light"), inUse(names))
        assertEquals(1f, KnownNames.boost(names[0], today), 0.0001f)
    }

    @Test
    fun aListThatNoLongerHasANameDropsIt() {
        var names = KnownNames.withSource(emptyList(), NameSource.CONTACT, listOf("Dana", "Omar"), today)
        names = KnownNames.withSource(names, NameSource.HOME, listOf("Omar"), today)
        names = KnownNames.withSource(names, NameSource.CONTACT, listOf("Dana"), today)
        assertEquals(setOf("Dana", "Omar"), inUse(names).toSet())
        names = KnownNames.withSource(names, NameSource.HOME, emptyList(), today)
        assertEquals(listOf("Dana"), inUse(names))
    }

    @Test
    fun aNameUsedOftenIsLeanedTowardMore() {
        var names = KnownNames.withSource(emptyList(), NameSource.CONTACT, listOf("Marlon", "Paula"), today)
        repeat(5) { names = talk(names, "Marlon", KnownNames.FROM_HEARD) }
        val steering = KnownNames.steering(names, today)
        assertEquals("Marlon", steering.first().first)
        assertEquals(KnownNames.BOOST_MAX, steering.first().second, 0.0001f)
        assertEquals(1f, steering.last().second, 0.0001f)
    }

    @Test
    fun usesFadeAndAConversationNameIsForgotten() {
        var names = talk(emptyList(), "Lunavista", KnownNames.FROM_TYPED)
        assertEquals(emptyList<String>(), inUse(names, today + 90))
        names = talk(names, "Fernway", KnownNames.FROM_TYPED, today + 400)
        assertEquals(listOf("Fernway"), names.map { it.text })
    }

    @Test
    fun aListedNameIsKeptHoweverLongUnused() {
        var names = KnownNames.withSource(emptyList(), NameSource.HOME, listOf("Garage Door"), today)
        names = talk(names, "Fernway", KnownNames.FROM_TYPED, today + 400)
        assertTrue("Garage Door" in inUse(names, today + 400))
    }

    @Test
    fun theListStaysBoundedAndKeepsWhatIsUsed() {
        var names = KnownNames.withSource(emptyList(), NameSource.CONTACT, List(KnownNames.MAX_NAMES + 200) { "Person $it" }, today)
        assertEquals(KnownNames.MAX_NAMES, names.size)
        names = talk(names, "Belmonte", KnownNames.FROM_TYPED)
        assertEquals(KnownNames.MAX_NAMES, names.size)
        assertTrue(names.any { it.text == "Belmonte" })
    }

    @Test
    fun onlySoManyNamesAreSteeredToward() {
        val names = KnownNames.withSource(emptyList(), NameSource.CONTACT, List(KnownNames.STEER_MAX + 50) { "Person $it" }, today)
        assertEquals(KnownNames.STEER_MAX, KnownNames.steering(names, today).size)
    }

    @Test
    fun aBlockedNameIsNotLearnedOrListedAgain() {
        val blocked = listOf(KnownName("Velmonde", emptySet(), 0.0, today, blocked = true))
        var names = talk(blocked, "Velmonde", KnownNames.FROM_TYPED, today + 1)
        names = KnownNames.withSource(names, NameSource.YOURS, listOf("Velmonde"), today + 1)
        assertEquals(emptyList<String>(), inUse(names, today + 1))
        assertTrue(names.single().blocked)
    }

    @Test
    fun aListedNameSaidAndHeardRightCounts() {
        val names = KnownNames.withSource(emptyList(), NameSource.HOME, listOf("Living Room Speaker", "Marlon", "Field & Fern"), today)
        assertEquals(listOf("Marlon"), KnownNames.saidNames("Marlon's list, please.", names))
        assertEquals(listOf("Living Room Speaker"), KnownNames.saidNames("Pause the living room speaker", names))
        assertEquals(emptyList<String>(), KnownNames.saidNames("Pause the living room TV", names))
    }

    // The second check: words that sound like a known name.

    private val names = listOf(
        "Marlon", "Orzo", "Belmonte", "Ab Rolls", "Harlow Bread", "Field & Fern", "SunPorch", "Theo", "Theodore", "Eva",
        "Provolone", "Tico Grill", "SeaCrest", "Lunavista", "La Colinita", "Step Counter", "Kitchen Light", "Moonbase", "kombucha",
    )

    @Test
    fun aMisheardNameSoundsLikeTheKnownOne() {
        assertEquals(listOf("Marlin" to "Marlon"), KnownNames.soundAlikes("Marlin wants a new backpack.", names))
        assertEquals(listOf("Velmonde" to "Belmonte"), KnownNames.soundAlikes("I had a serving of Velmonde rice.", names))
        assertEquals(listOf("Ozo" to "Orzo"), KnownNames.soundAlikes("I had a serving of Ozo and a serving of tuna.", names))
        assertEquals(listOf("Harlo Bread" to "Harlow Bread"), KnownNames.soundAlikes("It wasn't from Harlo Bread.", names))
    }

    @Test
    fun mishearingsWithinAQuarterOfTheLetters() {
        // Made-up names, bent the same ways as the mishearings a phone really made.
        fun match(heard: String) = KnownNames.soundAlikes(heard, names).map { it.second }
        assertEquals(listOf("Tico Grill"), match("a quarter of a Picogril burrito"))
        assertEquals(listOf("SeaCrest"), match("one pouch of Sea Cress tuna"))
        assertEquals(listOf("Lunavista"), match("play Lunavisda"))
        assertEquals(listOf("La Colinita"), match("two La Golinita tortillas"))
        assertEquals(listOf("Moonbase"), match("play Noon Base"))
        assertEquals(listOf("kombucha"), match("I made some Kambucha"))
        assertEquals(listOf("Provolone"), match("a slice of provlone cheese"))
    }

    @Test
    fun wordsRunTogetherMatchHoweverWritten() {
        assertEquals(listOf("abrolls" to "Ab Rolls"), KnownNames.soundAlikes("I also did twenty five abrolls", names))
        assertEquals(listOf("sun porch" to "SunPorch"), KnownNames.soundAlikes("turn on the sun porch", names))
        // "and" for "&" is the name itself, not a mishearing.
        assertEquals(emptyList<Pair<String, String>>(), KnownNames.soundAlikes("the tuna was field and fern", names))
    }

    @Test
    fun theNameItselfAndOrdinaryWordsAreLeftAlone() {
        val none = emptyList<Pair<String, String>>()
        assertEquals(none, KnownNames.soundAlikes("Marlon wants a kite.", names))
        assertEquals(none, KnownNames.soundAlikes("I did twenty five ab rolls with Theo", names))
        assertEquals(none, KnownNames.soundAlikes("Turn off the kitchen lights.", names))
        assertEquals(none, KnownNames.soundAlikes("we should plan on lending them the hollow bread", names))
        assertEquals(none, KnownNames.soundAlikes("Ella and Ava came over", names))
        assertEquals(none, KnownNames.soundAlikes("I walked eighteen minutes on the treadmill yesterday uphill.", names))
        assertEquals(none, KnownNames.soundAlikes("He also wants a headset with a mic for his PC.", names))
    }

    @Test
    fun wordsThatOnlyRoughlySoundAlikeAreNotMatched() {
        // The kinds of matches a phone really made when names were also compared by rough sound.
        val home = names + listOf("Porters", "Bretton", "The Arcade", "Porter's Pick")
        val none = emptyList<Pair<String, String>>()
        assertEquals(none, KnownNames.soundAlikes("I had a Porters Pick Strawberry Protein Shake. I also had Teriyaki chicken from Dale's Market.", home))
        assertEquals(none, KnownNames.soundAlikes("Maybe it's porters Pick, Teriyaki chicken. I don't know the brand name.", home))
    }

    @Test
    fun aSwappedWordOrAnOrdinaryFirstWordIsNotAMishearing() {
        // The kinds of false matches found by running a phone's real requests against its real names.
        val home = names + listOf("Garden Plus", "Docs", "Home", "Dale’s Market", "Brant")
        val none = emptyList<Pair<String, String>>()
        assertEquals(none, KnownNames.soundAlikes("Add a trowel to the Garden list for Robin.", home))
        assertEquals(none, KnownNames.soundAlikes("Does that include the Garden?", home))
        assertEquals(none, KnownNames.soundAlikes("I had rice. Some chicken tenderloins.", home))
        assertEquals(none, KnownNames.soundAlikes("a vanilla protein shake from Dale's Market", home))
        // Still caught: a name spelled out and heard as letters, and a name at the start of a sentence.
        assertEquals(listOf("PR ANT" to "Brant"), KnownNames.soundAlikes("His birthday is tomorrow PR ANT.", home))
        assertEquals(listOf("Marlin" to "Marlon"), KnownNames.soundAlikes("Okay. Marlin wants a new backpack.", home))
    }

    @Test
    fun theNoteNamesWhatWasHeardAndWhatItSoundsLike() {
        val note = KnownNames.hint("Marlin wants new drawing pens.", names)
        assertTrue(note.contains("\"Marlin\" sounds like \"Marlon\""))
        assertEquals("", KnownNames.hint("Turn off the kitchen lights.", names))
    }

    // Reading the user's lists.

    @Test
    fun homeAssistantNamesComeFromThingsPeopleTalkTo() {
        val states = listOf(
            JSONObject().put("entity_id", "light.sink").put("attributes", JSONObject().put("friendly_name", "Sink Light")),
            JSONObject().put("entity_id", "sensor.sink_power").put("attributes", JSONObject().put("friendly_name", "Sink Light Power")),
            JSONObject().put("entity_id", "media_player.speaker").put(
                "attributes",
                JSONObject().put("friendly_name", "Living Room Speaker").put("source_list", org.json.JSONArray(listOf("Radio", "YouTube"))),
            ),
            JSONObject().put("entity_id", "person.marlon").put("attributes", JSONObject().put("friendly_name", "Marlon")),
        )
        assertEquals(listOf("Sink Light", "Living Room Speaker", "Radio", "YouTube", "Marlon"), NameSources.fromStates(states))
    }

    @Test
    fun contactsGiveFullAndFirstNames() {
        assertEquals(
            listOf("Dana Whitfield", "Dana", "Paula", "Dr. Eva Lopez"),
            NameSources.fromContactNames(listOf("Dana Whitfield", "Paula", "someone@example.com", "Dr. Eva Lopez", "  ")),
        )
    }

    // Reply tags and the recognizer's pieces.

    @Test
    fun heardTagsComeOutOfTheReply() {
        val (clean, tagged) = ReplyTags.stripHeard("Logged it for Marlon. [heard: Marlin -> Marlon]")
        assertEquals("Logged it for Marlon.", clean)
        assertEquals(listOf("Marlon"), tagged)
        assertEquals("Done." to emptyList<String>(), ReplyTags.stripHeard("Done."))
    }

    @Test
    fun namesAreSplitIntoTheModelsPieces() {
        val vocab = setOf("▁R", "il", "ey", "▁Mar", "lon", "▁M", "a", "r", "l", "o", "n")
        assertEquals("▁R il ey", NameSteering.encode(vocab, "Riley"))
        assertEquals("▁R il ey ▁Mar lon", NameSteering.encode(vocab, "Riley Marlon"))
        assertNull(NameSteering.encode(vocab, "Zoë"))
    }
}
