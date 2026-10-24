package io.github.beacon.app.data.remote

import io.github.beacon.app.widget.WidgetTextLimits
import org.junit.Assert.*
import org.junit.Test

class WidgetTextLimitsTest {
    @Test fun captionsAreSingleLineAndCodePointBounded(){
        assertEquals("非常长的账户额…",WidgetTextLimits.cap("非常长的账户额度窗口名称",8))
        val result=WidgetTextLimits.cap("😀😀😀😀😀😀",4)
        assertEquals(4,result.codePointCount(0,result.length))
        assertFalse(WidgetTextLimits.cap("first\nsecond",20).contains('\n'))
    }
    @Test fun hugeNumbersUseAnExplicitExponentRatherThanOverflow(){
        assertEquals("24",WidgetTextLimits.number(24.0))
        assertTrue(WidgetTextLimits.number(999999999999999.0).contains("e",true))
        assertTrue(WidgetTextLimits.number(999999999999999.0).length<=8)
        assertEquals("—",WidgetTextLimits.number(Double.NaN))
    }
}
