package com.example.trex_kotlin

import com.example.trex_kotlin.store.OnboardingAnswers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The diet tab's calorie goal is now computed from what onboarding collected instead of from an
 * invented 170cm / 65kg body. That makes onboarding's text fields an input to a number the app
 * presents as advice, and those fields validate almost nothing: the sanitizer restricts the
 * character set to six digits and a dot, and the step gate only checks the answer is not blank.
 *
 * `10*kg + 6.25*cm - 5*age + 5` is unbounded in both directions, so without a plausibility filter
 * a typo becomes a starvation target or a negative one rendered on a progress gauge.
 */
class NutritionBodyTest {

    private fun answers(height: String, weight: String, age: String) = OnboardingAnswers(
        goalId = "lower",
        dayMask = 42,
        placeId = "gym",
        bodyweightOnly = false,
        equipmentMask = 7,
        gender = "male",
        height = height,
        weight = weight,
        age = age,
    )

    private val placeholder = NutritionBody(heightCm = 170, weightKg = 65, age = 30)

    @Test
    fun aRealAnswerIsUsed() {
        val body = answers("178.5", "72.0", "29").nutritionBody()

        assertEquals(NutritionBody(heightCm = 179, weightKg = 72, age = 29), body)
    }

    @Test
    fun theAveragePresetsOnboardingOffersAreAccepted() {
        // The "average" shortcuts write these exact strings, so they must survive the filter.
        assertEquals(NutritionBody(174, 74, 30), answers("173.5", "74.3", "30").nutritionBody())
        assertEquals(NutritionBody(160, 58, 30), answers("160.0", "58.4", "30").nutritionBody())
    }

    @Test
    fun noAnswersAtAllFallsBackToThePlaceholderBody() {
        assertEquals(placeholder, null.nutritionBody())
    }

    @Test
    fun blankAndNonNumericAnswersFallBack() {
        assertEquals(placeholder, answers("", "", "").nutritionBody())
        assertEquals(placeholder, answers("abc", "..", "-").nutritionBody())
    }

    @Test
    fun zeroesFallBackRatherThanProducingAStarvationTarget() {
        assertEquals(placeholder, answers("0", "0", "0").nutritionBody())
    }

    @Test
    fun absurdlyLargeAnswersFallBack() {
        // Six characters of digits get past the sanitizer, so "999999" is reachable by typing.
        assertEquals(placeholder, answers("999999", "999999", "999").nutritionBody())
    }

    @Test
    fun eachFieldFallsBackIndependently() {
        // A plausible height with a nonsense weight keeps the height.
        val body = answers("178", "0", "29").nutritionBody()

        assertEquals(178, body.heightCm)
        assertEquals(65, body.weightKg)
        assertEquals(29, body.age)
    }

    @Test
    fun theBoundariesOfEachPlausibleRangeAreInclusive() {
        assertEquals(90, answers("90", "70", "30").nutritionBody().heightCm)
        assertEquals(250, answers("250", "70", "30").nutritionBody().heightCm)
        assertEquals(25, answers("170", "25", "30").nutritionBody().weightKg)
        assertEquals(300, answers("170", "300", "30").nutritionBody().weightKg)
        assertEquals(10, answers("170", "70", "10").nutritionBody().age)
        assertEquals(120, answers("170", "70", "120").nutritionBody().age)
    }

    @Test
    fun justOutsideEachRangeFallsBack() {
        assertEquals(170, answers("89", "70", "30").nutritionBody().heightCm)
        assertEquals(170, answers("251", "70", "30").nutritionBody().heightCm)
        assertEquals(65, answers("170", "24", "30").nutritionBody().weightKg)
        assertEquals(65, answers("170", "301", "30").nutritionBody().weightKg)
        assertEquals(30, answers("170", "70", "9").nutritionBody().age)
        assertEquals(30, answers("170", "70", "121").nutritionBody().age)
    }

    @Test
    fun everyAcceptedBodyProducesAPositiveCalorieGoal() {
        // The property that matters: whatever survives the filter must yield a goal worth showing.
        for (height in listOf("90", "170", "250")) {
            for (weight in listOf("25", "65", "300")) {
                for (age in listOf("10", "30", "120")) {
                    val body = answers(height, weight, age).nutritionBody()
                    val bmr = 10 * body.weightKg + 6.25 * body.heightCm - 5 * body.age + 5
                    assertEquals(
                        "$height/$weight/$age must not produce a non-positive goal",
                        true,
                        bmr * 1.35 > 0,
                    )
                }
            }
        }
    }
}
