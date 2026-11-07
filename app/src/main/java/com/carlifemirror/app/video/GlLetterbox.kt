package com.carlifemirror.app.video

import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * SurfaceTexture (то, что отдаёт MediaProjection) -> OpenGL -> Surface энкодера.
 *
 * Экран телефона вписывается в кадр магнитолы целиком, с чёрными полями по краям,
 * поэтому картинка не растягивается. Тот же расчёт прямоугольника используется для
 * пересчёта координат касаний.
 */
class GlLetterbox(
    private val outSurface: Surface,
    private val videoW: Int,
    private val videoH: Int,
    private val screenW: Int,
    private val screenH: Int,
    private val captureW: Int,
    private val captureH: Int,
    initialFps: Int,
    private val log: (String) -> Unit,
) {
    private val thread = HandlerThread("mirror-gl")
    private lateinit var handler: Handler
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var textureId = 0
    private var program = 0
    private var aPos = 0
    private var aTex = 0
    private var uMatrix = 0
    private val matrix = FloatArray(16)
    private var surfaceTexture: SurfaceTexture? = null
    private var captureSurface: Surface? = null
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f,
            ))
            position(0)
        }

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var blackFrame = false
    @Volatile private var targetFps = initialFps.coerceIn(1, 60)
    @Volatile private var viewportX = 0
    @Volatile private var viewportY = 0
    @Volatile private var viewportW = videoW
    @Volatile private var viewportH = videoH
    private var lastDrawNs = 0L
    private var hasTexture = false
    private val drawTick = object : Runnable {
        override fun run() {
            if (!running) return
            if (paused) {
                handler.postDelayed(this, 100L)
                return
            }
            try {
                maybeDraw(SystemClock.elapsedRealtimeNanos())
            } catch (t: Throwable) {
                log("GL тик: ${t.javaClass.simpleName}: ${t.message}")
            }
            val periodNs = 1_000_000_000L / targetFps
            val delayMs = ((periodNs / 4) / 1_000_000L).coerceAtLeast(2L)
            handler.postDelayed(this, delayMs)
        }
    }

    /** Инициализирует GL и возвращает Surface для VirtualDisplay. */
    fun start(): Surface {
        thread.start()
        handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        handler.post {
            try {
                init()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(5, TimeUnit.SECONDS)) {
            stop()
            throw IllegalStateException("GL не инициализировался за 5 секунд")
        }
        error?.let {
            stop()
            throw IllegalStateException("Ошибка OpenGL: ${it.message}", it)
        }
        running = true
        handler.post(drawTick)
        return captureSurface ?: throw IllegalStateException("Surface захвата не создан")
    }

    fun setTargetFps(fps: Int) {
        targetFps = fps.coerceIn(1, 60)
    }

    /** PAUSE: не кормим энкодер новыми кадрами, пока ГУ не попросит продолжить. */
    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        // Тикер продолжает работать и во время паузы (раз в 100 мс), поэтому
        // после снятия паузы кадр появится максимум через 100 мс без второго
        // экземпляра Runnable.
    }

    /** Координаты в пикселях кадра магнитолы -> пиксели экрана телефона. null = чёрное поле. */
    fun mapTouch(x: Int, y: Int): PointF? {
        val vx = viewportX
        val vy = viewportY
        val vw = viewportW
        val vh = viewportH
        if (vw <= 0 || vh <= 0) return null
        if (x < vx || y < vy || x >= vx + vw || y >= vy + vh) return null
        val fx = (x - vx).toFloat() / vw
        val fy = (y - vy).toFloat() / vh
        return PointF(fx * screenW, fy * screenH)
    }

    /**
     * Заливает вход кодировщика чёрным. Нужно, когда MediaProjection остановлен
     * блокировкой экрана: сессия остаётся живой, но вместо зависшего кадра
     * магнитола получает чёрный.
     */
    fun showBlack() {
        if (!::handler.isInitialized) return
        blackFrame = true
        handler.post {
            if (!running) return@post
            try {
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                EGL14.eglSwapBuffers(display, eglSurface)
                hasTexture = false
                log("GL: MediaProjection потерян, показываю чёрный кадр")
            } catch (t: Throwable) {
                log("GL чёрный кадр: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    fun stop() {
        if (!::handler.isInitialized) return
        running = false
        val latch = CountDownLatch(1)
        handler.post {
            try {
                handler.removeCallbacks(drawTick)
                surfaceTexture?.setOnFrameAvailableListener(null)
                surfaceTexture?.release()
                surfaceTexture = null
                captureSurface?.release()
                captureSurface = null
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
                display = EGL14.EGL_NO_DISPLAY
                context = EGL14.EGL_NO_CONTEXT
                eglSurface = EGL14.EGL_NO_SURFACE
            } catch (t: Throwable) {
                log("GL stop: ${t.message}")
            } finally {
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun init() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize" }
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "eglChooseConfig"
        }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        eglSurface = EGL14.eglCreateWindowSurface(
            display, configs[0], outSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent" }

        program = buildProgram(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPosition")
        aTex = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(textureId)
        st.setDefaultBufferSize(captureW, captureH)
        st.setOnFrameAvailableListener({ onFrame() }, handler)
        surfaceTexture = st
        captureSurface = Surface(st)

        computeViewport()
        GLES20.glViewport(0, 0, videoW, videoH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(display, eglSurface)
        android.opengl.Matrix.setIdentityM(matrix, 0)
        log("GL: ${videoW}x$videoH, экран ${captureW}x$captureH, поле ${viewportX},${viewportY} ${viewportW}x$viewportH")
    }

    private fun computeViewport() {
        val srcAspect = captureW.toFloat() / captureH
        val dstAspect = videoW.toFloat() / videoH
        if (srcAspect > dstAspect) {
            viewportW = videoW
            viewportH = (videoW / srcAspect).toInt().coerceAtLeast(2)
            viewportX = 0
            viewportY = (videoH - viewportH) / 2
        } else {
            viewportH = videoH
            viewportW = (videoH * srcAspect).toInt().coerceAtLeast(2)
            viewportX = (videoW - viewportW) / 2
            viewportY = 0
        }
    }

    private fun onFrame() {
        if (!running || blackFrame) return
        try {
            val st = surfaceTexture ?: return
            st.updateTexImage()
            st.getTransformMatrix(matrix)
            hasTexture = true
            if (paused) return
            maybeDraw(SystemClock.elapsedRealtimeNanos())
        } catch (t: Throwable) {
            log("GL кадр: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * В отличие от старой версии рисуем не только по onFrameAvailable, но и по
     * таймеру. На статичном экране SurfaceTexture молчит, и без этого MediaCodec
     * не получает новых кадров (KEY_REPEAT_PREVIOUS_FRAME_AFTER на c2.qti ему не помог).
     */
    private fun maybeDraw(now: Long) {
        if (blackFrame) return
        if (!hasTexture) return
        val period = 1_000_000_000L / targetFps
        if (lastDrawNs != 0L && now - lastDrawNs < period - 2_000_000L) return
        lastDrawNs = now
        draw()
    }

    private fun draw() {
        GLES20.glViewport(0, 0, videoW, videoH)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        // GL-координаты идут снизу вверх; при центрировании y совпадает.
        GLES20.glViewport(viewportX, viewportY, viewportW, viewportH)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMatrix, 1, false, matrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, System.nanoTime())
        EGL14.eglSwapBuffers(display, eglSurface)
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val sh = GLES20.glCreateShader(type)
            GLES20.glShaderSource(sh, src)
            GLES20.glCompileShader(sh)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Шейдер: ${GLES20.glGetShaderInfoLog(sh)}" }
            return sh
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Программа: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX = """
            uniform mat4 uSTMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        private const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """
    }
}
