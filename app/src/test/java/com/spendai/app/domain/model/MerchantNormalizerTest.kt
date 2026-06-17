package com.spendai.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MerchantNormalizerTest {

    @Test fun `lowercases uppercase input`() {
        assertEquals("zomato", MerchantNormalizer.normalize("ZOMATO"))
    }

    @Test fun `trims whitespace`() {
        assertEquals("swiggy", MerchantNormalizer.normalize("   Swiggy   "))
    }

    @Test fun `strips pvt ltd suffix`() {
        assertEquals("zomato", MerchantNormalizer.normalize("Zomato Pvt Ltd"))
    }

    @Test fun `strips private limited suffix`() {
        assertEquals("amazon", MerchantNormalizer.normalize("Amazon Private Limited"))
    }

    @Test fun `strips ltd suffix`() {
        assertEquals("flipkart", MerchantNormalizer.normalize("Flipkart Ltd"))
    }

    @Test fun `strips india suffix`() {
        assertEquals("uber", MerchantNormalizer.normalize("Uber India"))
    }

    @Test fun `collapses multiple spaces`() {
        assertEquals("big basket", MerchantNormalizer.normalize("Big   Basket"))
    }

    @Test fun `strips trailing punctuation`() {
        assertEquals("zomato", MerchantNormalizer.normalize("Zomato..."))
    }

    @Test fun `keeps canonical name unchanged`() {
        assertEquals("zomato", MerchantNormalizer.normalize("Zomato"))
    }

    @Test fun `handles null input`() {
        assertEquals("", MerchantNormalizer.normalize(null))
    }

    @Test fun `handles blank input`() {
        assertEquals("", MerchantNormalizer.normalize("   "))
    }

    @Test fun `strips trailing comma after suffix`() {
        assertEquals("amazon", MerchantNormalizer.normalize("Amazon Pvt Ltd,"))
    }
}
