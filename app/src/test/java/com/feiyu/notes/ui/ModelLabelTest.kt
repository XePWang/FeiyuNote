package com.feiyu.notes.ui

import com.feiyu.notes.ai.ModelChoice
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelLabelTest {
    @Test fun deepSeekModelsAreAbbreviatedAndOthersKept() {
        assertEquals("dsf.low", shortModelLabel(ModelChoice("deepseek-flash", "low")))
        assertEquals("dsf.max", shortModelLabel(ModelChoice("deepseek-flash", "Max")))
        assertEquals("dsvp.high", shortModelLabel(ModelChoice("deepseek-v4-pro", "high")))
        assertEquals("my-model.low", shortModelLabel(ModelChoice("my-model", "low")))
        assertEquals("deepseek-.low", shortModelLabel(ModelChoice("deepseek-", "low")))
    }
}
