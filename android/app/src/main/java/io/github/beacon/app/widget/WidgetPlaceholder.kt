package io.github.beacon.app.widget

import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** Placeholder instructions must remain complete, including on narrow hosts. */
internal object WidgetPlaceholder {
    const val UNBOUND = "尚未绑定主题\n请在 App「设置 → 服务器管理 → 数据主题」中创建，再返回选择。"
    fun layout(message:String,width:Int,height:Int,textSize:Float,color:Int):StaticLayout {
        val paint=TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{
            this.color=color;typeface=Typeface.create("sans-serif",Typeface.NORMAL)
        }
        var size=textSize.coerceAtLeast(1f)
        fun build():StaticLayout {
            paint.textSize=size
            return StaticLayout.Builder.obtain(message,0,message.length,paint,width.coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setLineSpacing(0f,1.15f).build()
        }
        var result=build()
        while(result.height>height.coerceAtLeast(1)&&size>1f){size=(size-.5f).coerceAtLeast(1f);result=build()}
        return result
    }
}
