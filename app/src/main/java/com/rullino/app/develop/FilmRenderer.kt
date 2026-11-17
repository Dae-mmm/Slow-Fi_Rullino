package com.rullino.app.develop

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import com.rullino.app.data.HUE_KEYS
import com.rullino.app.data.HslAdj
import com.rullino.app.data.Preset
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Sviluppa una foto con lo shader della pellicola su un contesto OpenGL ES 3 fuori schermo.
 * Va creato, usato e rilasciato sempre sullo stesso thread.
 */
class FilmRenderer {
    private val display: EGLDisplay
    private val context: EGLContext
    private val surface: EGLSurface
    private val program: Int
    private val vbo: Int
    private val locations = HashMap<String, Int>()

    /** Lato massimo che la GPU accetta (limitato a 4096 per la memoria). */
    val maxSide: Int

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "EGL non disponibile" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL non inizializzato" }

        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "Nessuna configurazione EGL" }
        val config = configs[0]!!

        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "Contesto OpenGL ES 3 non disponibile" }
        surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent fallito" }

        program = buildProgram(FilmShader.VERTEX, FilmShader.FRAGMENT)

        val buf = IntArray(1)
        GLES30.glGenBuffers(1, buf, 0)
        vbo = buf[0]
        val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f, 1f)
        val fb = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad)
        fb.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, fb, GLES30.GL_STATIC_DRAW)

        val tmp = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, tmp, 0)
        val maxTex = tmp[0]
        GLES30.glGetIntegerv(GLES30.GL_MAX_RENDERBUFFER_SIZE, tmp, 0)
        maxSide = min(4096, min(maxTex, tmp[0]).takeIf { it > 0 } ?: 2048)
    }

    /** Restituisce una nuova bitmap sviluppata delle stesse dimensioni di [src]. */
    fun render(src: Bitmap, preset: Preset, seed: Float, look: DevelopLook = DevelopLook()): Bitmap {
        val w = src.width
        val h = src.height
        require(w <= maxSide && h <= maxSide) { "Immagine troppo grande per la GPU" }

        val ids = IntArray(2)
        GLES30.glGenTextures(2, ids, 0)
        val inTex = ids[0]
        val outTex = ids[1]

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inTex)
        setTexParams()
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, src, 0)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, outTex)
        setTexParams()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)

        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, outTex, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "Framebuffer incompleto" }

        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inTex)
        setUniforms(preset, w, h, seed, look)

        val aPos = GLES30.glGetAttribLocation(program, "aPos")
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(aPos)
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)

        // La riga 0 letta è quella in basso del framebuffer, che corrisponde alla riga 0
        // (in alto) della bitmap caricata: le due inversioni si annullano.
        val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        pixels.position(0)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.copyPixelsFromBuffer(pixels)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, fbo, 0)
        GLES30.glDeleteTextures(2, ids, 0)
        return out
    }

    fun release() {
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }

    private fun setTexParams() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun loc(name: String): Int = locations.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    /** Stesse conversioni di setUniforms() in Camera Oscura. */
    private fun setUniforms(preset: Preset, w: Int, h: Int, seed: Float, look: DevelopLook) {
        val p = preset.params
        GLES30.glUniform1i(loc("uImg"), 0)
        GLES30.glUniform1i(loc("uMode"), 0)
        GLES30.glUniform2f(loc("uRes"), w.toFloat(), h.toFloat())
        GLES30.glUniform1f(loc("uExposure"), p.exposure)
        GLES30.glUniform1f(loc("uTemp"), p.temperature / 100f)
        GLES30.glUniform1f(loc("uTint"), p.tint / 100f)
        GLES30.glUniform1f(loc("uGamma"), p.gamma.coerceIn(0.1f, 5f))
        GLES30.glUniform1f(loc("uContrast"), p.contrast / 100f)
        GLES30.glUniform1f(loc("uHighlights"), p.highlights / 100f)
        GLES30.glUniform1f(loc("uShadows"), p.shadows / 100f)
        GLES30.glUniform1f(loc("uFade"), p.fade / 100f)
        GLES30.glUniform1f(loc("uSat"), p.saturation / 100f)
        GLES30.glUniform1f(loc("uVib"), p.vibrance / 100f)

        val hsl = HUE_KEYS.map { p.hsl[it] ?: HslAdj() }
        GLES30.glUniform1fv(loc("uHslH"), 8, hsl.map { it.hue }.toFloatArray(), 0)
        GLES30.glUniform1fv(loc("uHslS"), 8, hsl.map { it.sat / 100f }.toFloatArray(), 0)
        GLES30.glUniform1fv(loc("uHslL"), 8, hsl.map { it.lum / 100f }.toFloatArray(), 0)

        val s = p.split
        GLES30.glUniform4f(loc("uSplit"), s.shadowHue, s.shadowAmount / 100f, s.highlightHue, s.highlightAmount / 100f)
        GLES30.glUniform1f(loc("uBalance"), s.balance / 100f)
        GLES30.glUniform1i(loc("uMono"), if (p.mono.enabled) 1 else 0)
        GLES30.glUniform3f(loc("uMix"), p.mono.red / 100f, p.mono.green / 100f, p.mono.blue / 100f)

        val isoFactor = sqrt(preset.iso.coerceAtLeast(1) / 400f)
        GLES30.glUniform3f(loc("uGrain"), p.grain.amount / 100f * isoFactor, p.grain.size, p.grain.roughness / 100f)
        GLES30.glUniform1f(loc("uVignette"), p.vignette / 100f)
        GLES30.glUniform1f(loc("uHalation"), p.halation / 100f)
        GLES30.glUniform1f(loc("uSeed"), seed)
        GLES30.glUniform1f(loc("uExpErr"), look.exposureError)
        GLES30.glUniform1i(loc("uPush"), look.push)
        GLES30.glUniform1i(loc("uSlide"), if (look.slide) 1 else 0)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val sh = GLES30.glCreateShader(type)
            GLES30.glShaderSource(sh, src)
            GLES30.glCompileShader(sh)
            val ok = IntArray(1)
            GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(sh)
                GLES30.glDeleteShader(sh)
                throw IllegalStateException("Shader non compilato: $log")
            }
            return sh
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("Shader non collegato: ${GLES30.glGetProgramInfoLog(p)}")
        return p
    }

    private companion object {
        /** EGL_OPENGL_ES3_BIT_KHR */
        const val EGL_OPENGL_ES3_BIT = 0x40
    }
}
