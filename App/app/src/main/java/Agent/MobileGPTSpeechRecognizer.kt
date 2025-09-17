package Agent

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * MobileGPT语音识别器类
 */
class MobileGPTSpeechRecognizer(mContext: Context) : TextToSpeech.OnInitListener {
    
    companion object;

    private var mTts: TextToSpeech
    private var ttsListener: UtteranceProgressListener
    
    /**
     * 语音转文本是否开启
     */
    var sttOn = false
    
    init {
        sttOn = false
        mTts = TextToSpeech(mContext, this)
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
            // 设置语言为英语（美国）
            mTts.language = Locale.US
            // mTts.language = Locale.getDefault()
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
        mTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_id")
        if (needResponse) {
            sttOn = true
        }
    }
}