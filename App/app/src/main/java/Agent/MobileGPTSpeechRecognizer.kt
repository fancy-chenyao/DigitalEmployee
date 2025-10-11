package Agent

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.GradientDrawable
import java.util.Locale

/**
 * MobileGPT语音识别器类
 */
class MobileGPTSpeechRecognizer(private val context: Context) : TextToSpeech.OnInitListener {
    
    companion object;

    private var mTts: TextToSpeech
    private var ttsListener: UtteranceProgressListener
    
    /**
     * 语音转文本是否开启
     */
    var sttOn = false
    
    init {
        sttOn = false
        mTts = TextToSpeech(context, this)
        ttsListener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                // TTS开始播放时的回调
            }
            
            override fun onDone(utteranceId: String?) {
                // TTS播放完成时的回调
            }
            
            override fun onError(utteranceId: String?) {
                // TTS播放出错时的回调
            }
        }
        mTts.setOnUtteranceProgressListener(ttsListener)
    }
    
    /**
     * TTS初始化回调
     * @param status 初始化状态
     */
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // 在这里设置您的首选语言和其他TTS设置
            // 优先设置为中文普通话（简体）
            val zhResult = mTts.setLanguage(Locale.SIMPLIFIED_CHINESE)
            if (zhResult == TextToSpeech.LANG_MISSING_DATA || zhResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                // 尝试中国地区中文
                val zhCn = mTts.setLanguage(Locale.CHINA)
                if (zhCn == TextToSpeech.LANG_MISSING_DATA || zhCn == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // 回退到系统默认语言
                    mTts.language = Locale.getDefault()
                }
            }

            // 可选：适当提高语速和音调，让中文更自然
            mTts.setSpeechRate(1.0f)
            mTts.setPitch(1.0f)
        } else {
            // 处理TTS初始化失败
        }
    }
    
    /**
     * 播放语音
     * @param text 要播放的文本
     * @param needResponse 是否需要响应
     */
    fun speak(text: String, needResponse: Boolean) {
        // 暂时改为 Toast 提示，并支持长文本与更长显示时间
        showEnhancedToast(text)
        // 保留原逻辑，后续恢复时只需改回：
//        mTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_id")
        if (needResponse) {
            sttOn = true
        }
    }

    /**
     * 使用可多行且可延长显示时间的 Toast
     */
    private fun showEnhancedToast(message: String) {
        val appCtx = context.applicationContext

        // 自定义文本视图，支持多行换行显示
        val tv = TextView(appCtx).apply {
            text = message
            setTextColor(0xFF222222.toInt())
            textSize = 14f
            setPadding(32, 24, 32, 24)
            maxLines = 10
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            // 圆角背景
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f
                setColor(0xF2FFFFFF.toInt()) // 半透明白色
                setStroke(1, 0x22000000) // 细微描边
            }
        }

        val toast = Toast(appCtx).apply {
            view = tv
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, 160)
            duration = Toast.LENGTH_LONG
        }

        // 根据文本长度动态决定总显示时长（上限15s）
        val totalDurationMs = (2000 + message.length * 80).coerceAtMost(15000)
        val singleShowMs = 3500 // LENGTH_LONG 约 3.5s
        val repeatTimes = kotlin.math.max(1, kotlin.math.ceil(totalDurationMs / singleShowMs.toDouble()).toInt())

        val handler = Handler(Looper.getMainLooper())
        fun showWithRepeat(timesLeft: Int) {
            if (timesLeft <= 0) return
            toast.show()
            handler.postDelayed({ showWithRepeat(timesLeft - 1) }, singleShowMs.toLong())
        }
        showWithRepeat(repeatTimes)
    }
}