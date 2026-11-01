package com.havoice.assistant

import com.havoice.assistant.AddressCheck.Address
import com.havoice.assistant.AddressCheck.Result
import org.junit.Assert.assertEquals
import org.junit.Test

class AddressCheckTest {
    private val oak = Address("3140", "East Oakland Street", "Springfield", "Illinois")

    @Test
    fun sameAddressIsSure() {
        assertEquals(Result.Found(oak, sure = true), AddressCheck.judge("3140 East Oakland Street", listOf(oak)))
        assertEquals(Result.Found(oak, sure = true), AddressCheck.judge("3140 E Oakland St", listOf(oak)))
        // A city said after the street isn't compared with the street.
        assertEquals(Result.Found(oak, sure = true), AddressCheck.judge("3140 East Oakland Street Springfield", listOf(oak)))
    }

    @Test
    fun slightlyMisheardStreetIsStillSure() {
        assertEquals(Result.Found(oak, sure = true), AddressCheck.judge("3140 Eastoak Land Street", listOf(oak)))
    }

    @Test
    fun differentHouseNumberAsks() {
        val other = oak.copy(houseNumber = "3104")
        assertEquals(Result.Found(other, sure = false), AddressCheck.judge("3140 East Oakland Street", listOf(other)))
    }

    @Test
    fun streetOnlyResultAsks() {
        val street = oak.copy(houseNumber = null)
        assertEquals(Result.Found(street, sure = false), AddressCheck.judge("3140 East Oakland Street", listOf(street)))
    }

    @Test
    fun picksTheMatchingResult() {
        val wrong = Address("3140", "West Elm Avenue", "Springfield", "Illinois")
        assertEquals(Result.Found(oak, sure = true), AddressCheck.judge("3140 East Oakland Street", listOf(wrong, oak)))
    }

    @Test
    fun unrelatedResultIsNotFound() {
        val wrong = Address("12", "Birch Lane", "Shelbyville", "Illinois")
        assertEquals(Result.NotFound, AddressCheck.judge("3140 East Oakland Street", listOf(wrong)))
        assertEquals(Result.NotFound, AddressCheck.judge("3140 East Oakland Street", emptyList()))
    }

    @Test
    fun spokenAndQueryText() {
        assertEquals("3140 East Oakland Street, Springfield", oak.spoken)
        assertEquals("3140 East Oakland Street, Springfield, Illinois", oak.query)
    }

    @Test
    fun cityIsNamedOnlyAfterTheStreet() {
        assertEquals(false, AddressCheck.namesCity("3140 East Oakland Street"))
        assertEquals(false, AddressCheck.namesCity("3140 East Oakland Street."))
        assertEquals(true, AddressCheck.namesCity("3140 East Oakland Street Springfield"))
        assertEquals(true, AddressCheck.namesCity("3140 East Oakland, Springfield"))
    }

    @Test
    fun distanceBetweenCities() {
        // Chicago to New York is about 1,145 km; across town is a few.
        val km = AddressCheck.kilometers(41.878, -87.630, 40.713, -74.006)
        assertEquals(1145.0, km, 20.0)
        assertEquals(5.0, AddressCheck.kilometers(41.878, -87.630, 41.923, -87.630), 0.2)
    }
}
