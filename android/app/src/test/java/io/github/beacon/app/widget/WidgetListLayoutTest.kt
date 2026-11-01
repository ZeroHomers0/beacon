package io.github.beacon.app.widget

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WidgetListLayoutTest {
    private val wide=360f
    private val pad=WidgetGeometry.inset(360,180)
    private val scale=WidgetGeometry.scale(360,180)

    private fun plan(vararg titles:String,count:Int=12,size:Int=0,columns:Int=2,lines:Int=1)=
        WidgetListLayout.plan(titles.size,count,columns,lines,120f,150f,size.toFloat(),scale){i,size->titles[i].length*size*scale}

    @Test fun autoSizeShrinksUntilEveryVisibleTitleFits() {
        val long="成人感染幽门螺杆菌很难自愈"
        // 12 号汉字约 9.8px，16 号放不下 13 个汉字。
        val auto=plan(long,long,long)
        assertTrue("应退到能放下的最大字号",auto.textSize<16f)
        assertEquals(3,auto.limit)
        // 强制固定 16 号时不缩小，让渲染端省略。
        assertEquals(16f,plan(long,long,long,size=16).textSize)
    }

    @Test fun shortTitlesKeepTheLargestSize() {
        val auto=plan("金价","我起床了厉娜","金价长期走势将转向")
        assertEquals(16f,auto.textSize)
        assertTrue(auto.limit>0)
    }

    @Test fun columnPreferenceWinsButNeverCollapsesToUnreadableWidth() {
        assertEquals(2,WidgetListLayout.columns(2,false,wide,pad,scale))
        assertEquals(1,WidgetListLayout.columns(1,true,wide,pad,scale))
        // 用户没指定时跟随卡片宽度。
        assertEquals(2,WidgetListLayout.columns(0,true,wide,pad,scale))
        assertEquals(1,WidgetListLayout.columns(0,false,wide,pad,scale))
        // 180dp 方卡即使强行双列，每列仍有约 7.8 个最小号汉字，允许保留。
        assertEquals(2,WidgetListLayout.columns(2,true,180f,WidgetGeometry.inset(180,180),WidgetGeometry.scale(180,180)))
        // 更窄的可用宽度会把每列压到 6 个汉字以下，退回单列。
        assertEquals(1,WidgetListLayout.columns(2,true,120f,WidgetGeometry.inset(120,120),WidgetGeometry.scale(120,120)))
    }

    @Test fun perColumnNeverReturnsZero() {
        assertEquals(1,WidgetListLayout.perColumn(1f,24f,1f,2))
        assertTrue(WidgetListLayout.perColumn(120f,12f,scale,1)>=4)
    }

    @Test fun listCountAndStartBoundTheVisibleRows() {
        val titles=(1..12).map{"第${it}条热搜标题"}
        assertEquals(4,plan(*titles.toTypedArray(),count=4).limit)
        // 一列五行时双列最多十条。
        assertEquals(10,plan(*titles.toTypedArray(),count=12).limit)
    }

    @Test fun listColumnsAndAutomaticSizeAreValidated() {
        val list=JSONObject().put("template","text_list")
        assertNull(WidgetTuning.error(JSONObject(list.toString()).put("list_columns",0)))
        assertNull(WidgetTuning.error(JSONObject(list.toString()).put("list_columns",2).put("text_size",0)))
        assertNotNull(WidgetTuning.error(JSONObject(list.toString()).put("list_columns",3)))
        assertNotNull(WidgetTuning.error(JSONObject(list.toString()).put("text_size",13)))
        assertNotNull(WidgetTuning.error(JSONObject(list.toString()).put("text_size",-1)))
        // 「自动」只属于文字列表，混到别的模板会被静默改成 12 号，直接拒绝。
        assertNotNull(WidgetTuning.error(JSONObject().put("template","text").put("text_size",0)))
    }

    @Test fun automaticSizeStaysEquivalentToTheExplicitDefault() {
        assertTrue(WidgetConfiguration.equivalent("{}","""{"list_columns":0,"text_size":16}""","WIDE"))
        assertFalse(WidgetConfiguration.equivalent("{}","""{"list_columns":1}""","WIDE"))
        // 文字列表的默认字号是「自动」，所以没写 text_size 和写了 0 是同一份配置。
        assertTrue(WidgetConfiguration.equivalent("""{"template":"text_list"}""","""{"template":"text_list","text_size":0}""","WIDE"))
        assertFalse(WidgetConfiguration.equivalent("""{"template":"text_list"}""","""{"template":"text_list","text_size":16}""","WIDE"))
        assertEquals(0,WidgetListLayout.preferredSize(JSONObject().put("template","text_list")))
        assertEquals(14,WidgetListLayout.preferredSize(JSONObject().put("template","text_list").put("text_size",14)))
        assertEquals(16,WidgetListLayout.preferredSize(JSONObject().put("template","text")))
    }

    @Test fun wideCardRendersEveryTitleWithoutEllipsisAtAutomaticSize() {
        val titles=listOf("成人感染幽门螺杆菌很难自愈","解读持之以恒推进全面从严治党","人会在某一刻突然理解塞翁失马","法定放假总数已增至13天")
        val columns=WidgetListLayout.columns(0,true,wide,pad,scale)
        val cellText=(wide-2*pad)/columns-8*scale
        val auto=WidgetListLayout.plan(titles.size,12,columns,1,120f,cellText,0f,scale){i,size->titles[i].length*size*scale}
        assertTrue(auto.textSize<=14f)
        titles.take(auto.limit).forEach{assertTrue(it.length*auto.textSize*scale<=cellText)}
    }

    @Test fun rightAlignedLabelStealsWidthFromTheTitleInsteadOfOverlapping() {
        val titles=Array(4){"成人感染幽门螺杆菌很难自愈"}
        val full=WidgetListLayout.plan(titles.size,12,1,1,120f,200f,0f,scale){i,size->titles[i].length*size*scale}
        // 右侧留出 60px 画热度小字，同样的标题必须退到更小字号才能完整显示。
        val shrunk=WidgetListLayout.plan(titles.size,12,1,1,120f,200f,0f,scale){i,size->titles[i].length*size*scale+60f}
        assertEquals(16f,full.textSize)
        assertTrue(shrunk.textSize<full.textSize)
    }

    @Test fun crampedTwoColumnReportsThatNothingFits() {
        // 双列加上热度后缀后，12 号也放不下最长标题：plan 必须如实报 fits=false，
        // 渲染端据此退回单列，而不是悄悄省略一半标题。
        val titles=Array(10){"成人感染幽门螺杆菌很难自愈"}
        val cellText=(wide-2*pad)/2-8*scale
        val cramped=WidgetListLayout.plan(titles.size,12,2,1,120f,cellText,0f,scale){i,size->titles[i].length*size*scale+40f}
        assertFalse(cramped.fits)
        assertEquals(12f,cramped.textSize)
        // 换成单列后同样的内容放得下。
        val single=WidgetListLayout.plan(titles.size,12,1,1,120f,wide-2*pad,0f,scale){i,size->titles[i].length*size*scale+40f}
        assertTrue(single.fits)
    }
}
