package com.havoice.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneActionsTest {
    private fun parse(text: String) = PhoneActions.parse(text, NavigationApp.GOOGLE_MAPS)

    @Test
    fun navigation() {
        assertEquals(
            PhoneAction.Navigate("Target on Main Street", TravelMode.DRIVING, NavigationApp.GOOGLE_MAPS),
            parse("Hey, take me to Target on Main Street."),
        )
        assertEquals(
            PhoneAction.Navigate("the nearest gas station", TravelMode.DRIVING, NavigationApp.WAZE),
            parse("Can you navigate to the nearest gas station using Waze?"),
        )
        assertEquals(PhoneAction.Navigate("the park", TravelMode.WALKING, NavigationApp.GOOGLE_MAPS), parse("walking directions to the park"))
        assertEquals(PhoneAction.Navigate("home", TravelMode.DRIVING, NavigationApp.GOOGLE_MAPS), parse("Take me home"))
        assertEquals(PhoneAction.Navigate("Costco", TravelMode.DRIVING, NavigationApp.GOOGLE_MAPS), parse("Directions to Costco, please."))
    }

    @Test
    fun houseNumbers() {
        val cases = mapOf(
            "three thousand one hundred forty West Oak Street" to "3140 West Oak Street",
            "three thousand one forty West Oak Street" to "3140 West Oak Street",
            "thir thousand one forty West Oak Street" to "3140 West Oak Street",
            "thirty one forty West Oak Street" to "3140 West Oak Street",
            "thirty-one forty West Oak Street" to "3140 West Oak Street",
            "three one four zero West Oak Street" to "3140 West Oak Street",
            "twelve oh five Elm Avenue" to "1205 Elm Avenue",
            "one twenty three Maple Drive" to "123 Maple Drive",
            "four hundred and five Pine Road" to "405 Pine Road",
            "twenty one hundred Lake Boulevard" to "2100 Lake Boulevard",
            "eighteen Birch Lane" to "18 Birch Lane",
            "3140 West Oak Street" to "3140 West Oak Street",
            // Numbered streets.
            "twelve twelve one hundred first Avenue" to "1212 101st Avenue",
            "thirty one forty twenty third Street" to "3140 23rd Street",
            "four oh one one hundred and twelfth Street" to "401 112th Street",
            "one hundred first Avenue" to "101st Avenue",
            "eight seventy two West thirty fourth Street" to "872 West 34th Street",
            "one hundred one East one hundred first Street" to "101 East 101st Street",
            "nine hundred and fifty six Elm Avenue" to "956 Elm Avenue",
            "four hundred and twenty three Fox Run Lane" to "423 Fox Run Lane",
            "twelve South fifth Street" to "12 South 5th Street",
            "twelve East Main Street" to "12 East Main Street",
            // Not house numbers: left as said.
            "Seven Eleven on Main Street" to "Seven Eleven on Main Street",
            "Five Guys" to "Five Guys",
            "Target on Main Street" to "Target on Main Street",
        )
        cases.forEach { (said, sent) -> assertEquals(said, sent, PhoneActions.withHouseNumber(said)) }
        assertEquals(
            PhoneAction.Navigate("3140 West Oak Street", TravelMode.DRIVING, NavigationApp.GOOGLE_MAPS),
            parse("Take me to three thousand one forty West Oak Street."),
        )
        // Pauses become sentence breaks in the transcript.
        assertEquals(
            PhoneAction.Navigate("956 Elm Street", TravelMode.DRIVING, NavigationApp.GOOGLE_MAPS),
            parse("I said, take me to nine hundred and fifty six. Elm Street."),
        )
    }

    @Test
    fun timers() {
        assertEquals(PhoneAction.Timer(600), parse("Set a timer for 10 minutes."))
        assertEquals(PhoneAction.Timer(1500), parse("set a timer for twenty-five minutes"))
        assertEquals(PhoneAction.Timer(300), parse("Start a five minute timer"))
        assertEquals(PhoneAction.Timer(5400), parse("Set a timer for an hour and a half"))
        assertEquals(PhoneAction.Timer(5400), parse("set a timer for one and a half hours"))
        assertEquals(PhoneAction.Timer(1800), parse("Set a timer for half an hour."))
        assertEquals(PhoneAction.Timer(1800), parse("set a half hour timer"))
        assertEquals(PhoneAction.Timer(90), parse("set a timer for 1 minute and 30 seconds"))
        assertEquals("1 hour and 30 minutes", PhoneActions.describe(5400))
    }

    @Test
    fun leftForHomeAssistant() {
        listOf(
            "Turn on the kitchen lights",
            "Pause the living room speaker",
            "Next song",
            "How much time is left on the timer?",
            "Cancel the timer",
            "Set the thermostat to 72",
            "Is Target open today?",
            "Play some jazz in the kitchen",
            "Add take me to the store to my notes",
            "set a timer",
            // Music is Home Assistant's (SpotifyPlus scripts), on the phone or any speaker.
            "Play Dare You by Papadosio on Spotify",
            "Pause Spotify",
            "Add this song to Road Trip",
        ).forEach { assertNull(it, parse(it)) }
    }
}
