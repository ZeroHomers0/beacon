package io.github.beacon.app.ui.components

import android.app.ActivityManager
import android.content.Context
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.*

/** One failure disables the optical path for this scaffold; no saved preference or retry loop. */
internal class BeaconGlassState(supported: Boolean) {
    var available by mutableStateOf(supported)
        private set

    fun disable() { available = false }
}

internal val LocalBeaconGlass = staticCompositionLocalOf<BeaconGlassState?> { null }
internal val LocalBeaconGlassBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

internal fun supportsBeaconGlass(sdk: Int, hardware: Boolean, lowRam: Boolean, shader: Boolean) =
    sdk >= 33 && hardware && !lowRam && shader

@Composable
internal fun rememberBeaconGlassState(): BeaconGlassState {
    val context = LocalContext.current
    val view = LocalView.current
    val inspection = LocalInspectionMode.current
    return remember(view, inspection) {
        val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.isLowRamDevice ?: true
        val shader = !inspection && runCatching {
            val program = RuntimeShader(BeaconGlassShader)
            android.graphics.RenderEffect.createRuntimeShaderEffect(program, "background")
        }.isSuccess
        BeaconGlassState(supportsBeaconGlass(Build.VERSION.SDK_INT, view.isHardwareAccelerated, lowRam, shader))
    }
}

/** Background only: text/icons never enter the blur or refraction pipeline. */
@Composable
internal fun Modifier.beaconGlassMaterial(
    fallback: Color,
    shape: Shape = RectangleShape,
): Modifier {
    val state = LocalBeaconGlass.current
    if (!miuix() || state?.available != true) return background(fallback, shape)
    val backdrop = LocalBeaconGlassBackdrop.current
    val dark = fallback.luminance() < .5f
    val tint = fallback.copy(alpha = if (dark) .78f else .66f)
    val base = background(fallback, shape)
    // Guard only the material layer. Business content exceptions must still propagate normally.
    val optical = if (backdrop == null) Modifier else Modifier
        .drawWithContent {
            if (state.available && drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                try { drawContent() } catch (_: RuntimeException) { state.disable() }
            } else if (state.available) state.disable()
        }
        .drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                effect(BlurEffect(18.dp.toPx(), 18.dp.toPx(), TileMode.Clamp))
                runtimeShaderEffect("beacon-soft-glass-v1", BeaconGlassShader, "background") {
                    setFloatUniform("extent", size.width, size.height)
                    setFloatUniform("depth", 1.5.dp.toPx())
                }
            },
            onDrawSurface = { drawRect(tint) },
        )
    // Source-contained controls use a tint/highlight material without sampling their own layer.
    return this.then(base).then(optical)
        .background(Brush.verticalGradient(listOf(
            Color.White.copy(alpha = if (dark) .07f else .20f),
            Color.Transparent,
            Color.Black.copy(alpha = if (dark) .05f else .015f),
        )), shape)
        .border(.5.dp, Color.White.copy(alpha = if (dark) .12f else .38f), shape)
}

@Composable
internal fun BeaconGlassSurface(
    fallback: Color,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        Box(Modifier.matchParentSize().beaconGlassMaterial(fallback, shape))
        content()
    }
}

// Original AGSL: a shallow lens at the surface perimeter, with directional soft light.
private const val BeaconGlassShader = """
uniform shader background;
uniform float2 extent;
uniform float depth;
half4 main(float2 p) {
    float2 safeExtent = max(extent, float2(1.0));
    float2 uv = p / safeExtent;
    float2 edge = min(p, safeExtent - p);
    float rim = 1.0 - smoothstep(0.0, 18.0, min(edge.x, edge.y));
    float2 normal = normalize((uv - 0.5) + float2(0.0001));
    float2 samplePoint = clamp(p - normal * depth * rim * rim, float2(0.5), safeExtent - 0.5);
    half4 color = background.eval(samplePoint);
    float light = rim * max(dot(normal, normalize(float2(-0.5, -0.9))), 0.0) * 0.08;
    color.rgb = mix(color.rgb, half3(color.a), half(light));
    return color;
}
"""
