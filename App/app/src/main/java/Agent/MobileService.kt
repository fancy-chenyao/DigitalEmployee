package Agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
//import Agent.AskPopUp
import Agent.GPTMessage
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import controller.GenericElement
import controller.ElementController

/**
 * MobileGPT普通服务类，负责处理与服务器通信
 */
class MobileService : Service() {
    companion object {
        private const val TAG = "MobileGPT_Service"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "MobileGPTServiceChannel"
    }

    private val binder = LocalBinder()
    private lateinit var wm: WindowManager
    private var mClient: MobileGPTClient? = null
    private lateinit var mSpeech: MobileGPTSpeechRecognizer
//    lateinit var mAskPopUp: AskPopUp
    private var mMobileGPTGlobal: MobileGPTGlobal? = null
    private var nodeMap: HashMap<Int, GenericElement>? = null
    private var instruction: String? = null
    private var targetPackageName: String? = null
    var xmlPending = false
    var screenNeedUpdate = false
    var firstScreen = false
    private var screenUpdateWaitRunnable: Runnable? = null
    private var screenUpdateTimeoutRunnable: Runnable? = null
    private var clickRetryRunnable: Runnable? = null
    private var actionFailedRunnable: Runnable? = null
    private lateinit var mExecutorService: ExecutorService
    private val mainThreadHandler = Handler(Looper.getMainLooper())
    private var currentScreenXML = ""
    private var currentScreenShot: Bitmap? = null
    private lateinit var fileDirectory: File
    private var screenUpdateRunnable: Runnable? = null
    private var isScreenUpdateEnabled = false

    /**
     * 本地绑定器类
     */
    inner class LocalBinder : Binder() {
        fun getService(): MobileService = this@MobileService
    }

    /**
     * 广播接收器，用于接收指令
     */
    private val stringReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MobileGPTGlobal.STRING_ACTION) {
                reset()
                instruction = intent.getStringExtra(MobileGPTGlobal.INSTRUCTION_EXTRA)
                Log.d(TAG, "receive broadcast")
                mExecutorService.execute { 
                    initNetworkConnection()
                    mClient?.sendInstruction(instruction!!)
                    // 发送指令后启动屏幕更新
                    mainThreadHandler.post {
                        startPeriodicScreenUpdate()
                    }
                }
            }
        }
    }

    /**
     * 创建通知渠道 (Android 8.0+)
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MobileGPT Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * 创建前台服务通知
     */
    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("MobileGPT Service")
                .setContentText("MobileGPT service is running")
                .setSmallIcon(android.R.drawable.ic_menu_info_details) // 使用自定义图标
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("MobileGPT Service")
                .setContentText("MobileGPT service is running")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build()
        }
    }

    /**
     * 服务绑定时返回IBinder
     */
    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    /**
     * 服务创建时的初始化
     */
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MobileService onCreate")
        
        // 创建前台服务通知
        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        mExecutorService = Executors.newSingleThreadExecutor()
        
        // 注册广播接收器
        val intentFilter = IntentFilter(MobileGPTGlobal.STRING_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stringReceiver, intentFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stringReceiver, intentFilter)
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        mSpeech = MobileGPTSpeechRecognizer(this)
        mMobileGPTGlobal = MobileGPTGlobal.getInstance()

        // 延迟初始化网络连接，不阻塞服务启动
        mExecutorService.execute {
            initNetworkConnection()
            // 在网络连接初始化后再初始化 mAskPopUp
//            mainThreadHandler.post {
//                mAskPopUp = AskPopUp(this, mClient!!, mSpeech)
//            }
        }
        
        Log.d(TAG, "MobileService 初始化完成")
        
        // 不自动启动屏幕更新，等待用户指令
        // startPeriodicScreenUpdate()
    }

    /**
     * 服务启动时调用
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    /**
     * 发送回答
     */
    fun sendAnswer(infoName: String, question: String, answer: String) {
        val qaString = "$infoName\\$question\\$answer"
        mClient?.sendQA(qaString)
    }

    /**
     * 处理服务器响应
     */
    @SuppressLint("DefaultLocale")
    private fun handleResponse(message: String) {
        var actionSuccess = true
        Log.d(TAG, "Received message: $message")

        // 选择应用
        if (message.startsWith("##$$##")) {
            val selectedApp = message.substring(6)
            targetPackageName = selectedApp
            fileDirectory = File(getExternalFilesDir(null), targetPackageName)
            if (!fileDirectory.exists()) {
                fileDirectory.mkdirs()
            }
            mExecutorService.execute { launchAppAndInit(selectedApp) }
            return
        } else if (message.startsWith("$$##$$")) {
            val subtask = message.substring(6)
            return
        } else if (message.startsWith("$$$$$")) {
            // 断开服务器连接
            Log.d(TAG, "-----------Task finished--------")
            mSpeech.speak("任务已完成。", false)
            mClient?.disconnect()
            reset()
            return
        }

        try {
            val gptMessage = GPTMessage(message)
            val action = gptMessage.getActionName()
            val args = gptMessage.getArgs()

            when (action) {
                "speak" -> {
                    val content = args.get("message") as String
                    mSpeech.speak(content, false)
                    return
                }
                "ask" -> {
                    val question = args.get("question") as String
                    val infoName = args.get("info_name") as String
                    handleAsk(infoName, question)
                }
                in MobileGPTGlobal.AVAILABLE_ACTIONS -> {
                    // 注意：普通服务无法直接执行UI操作，需要通过其他方式实现
                    Log.d(TAG, "UI action requested: $action")
                    // 可以发送广播或使用其他机制来执行UI操作
                }
            }
        } catch (e: JSONException) {
            val error = "The action has wrong parameters. Make sure you have put all parameters correctly."
            e.printStackTrace()
            mExecutorService.execute { mClient?.sendError(error) }
            Log.e(TAG, "wrong json format")
        }
    }

    /**
     * 处理问题
     */
    private fun handleAsk(info: String, question: String) {
        Log.d(TAG, "Asking question: $question")
//        mAskPopUp.setQuestion(info, question)
//        mSpeech.speak(question, true)
//        mAskPopUp.showPopUp()
    }


    /**
     * 保存当前屏幕信息
     */
    private fun saveCurrScreen() {
        screenNeedUpdate = false
        xmlPending = false
        firstScreen = false
        saveCurrScreenXML()
    }

    /**
     * 保存当前屏幕XML
     * 通过ActivityTracker获取当前Activity，使用ElementController获取元素树并转换为XML字符串
     */
    private fun saveCurrScreenXML() {
        nodeMap = HashMap()
        Log.d(TAG, "Node Renewed!!!!!!!")
        
        // 获取当前Activity
        val currentActivity = ActivityTracker.getCurrentActivity()
        if (currentActivity == null) {
            Log.w(TAG, "当前Activity为空，无法获取元素树")
            currentScreenXML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<elementTree type=\"error\">\n  <element id=\"error\" type=\"Error\" text=\"当前Activity为空\" clickable=\"false\" focusable=\"false\" enabled=\"false\" visible=\"false\" bounds=\"\"/>\n</elementTree>"
            return
        }
        
        // 使用ElementController获取当前元素树
        ElementController.getCurrentElementTree(currentActivity) { genericElement ->
            // 将GenericElement转换为XML字符串
            currentScreenXML = convertGenericElementToXmlString(genericElement)
            Log.d(TAG, "元素树XML生成完成")
        }
    }

    /**
     * 生成简化的XML
     * @param activity 当前Activity
     * @return XML字符串
     */
    private fun generateSimpleXML(activity: android.app.Activity): String {
        val activityName = activity.javaClass.simpleName
        val packageName = activity.packageName
        
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes" ?>
<hierarchy>
  <node resource-id="root" class="android.widget.FrameLayout" text="" clickable="false" enabled="true" bounds="[0,0][1080,1920]">
    <node resource-id="android:id/content" class="android.widget.FrameLayout" text="" clickable="false" enabled="true" bounds="[0,0][1080,1920]">
      <node resource-id="activity_info" class="android.widget.LinearLayout" text="Activity: $activityName" clickable="false" enabled="true" bounds="[0,0][1080,200]">
        <node resource-id="package_info" class="android.widget.TextView" text="Package: $packageName" clickable="false" enabled="true" bounds="[10,10][1070,50]"/>
        <node resource-id="activity_name" class="android.widget.TextView" text="Activity: $activityName" clickable="false" enabled="true" bounds="[10,60][1070,100]"/>
        <node resource-id="timestamp" class="android.widget.TextView" text="Time: ${System.currentTimeMillis()}" clickable="false" enabled="true" bounds="[10,110][1070,150]"/>
      </node>
      <node resource-id="test_button" class="android.widget.Button" text="Test Button" clickable="true" enabled="true" bounds="[100,300][980,400]"/>
      <node resource-id="test_edit" class="android.widget.EditText" text="" clickable="true" enabled="true" bounds="[100,450][980,550]"/>
    </node>
  </node>
</hierarchy>"""
    }

    /**
     * 将GenericElement转换为XML字符串
     * @param element 要转换的GenericElement
     * @return XML字符串
     */
    private fun convertGenericElementToXmlString(element: GenericElement): String {
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes" ?>
<hierarchy>
${element.children.joinToString("") { it.toXmlString(1) }}
</hierarchy>"""
    }
//        return """<?xml version="1.0" encoding="UTF-8"?>
//<elementTree type="${element.type.escapeXml()}">
//${element.children.joinToString("") { it.toXmlString(1) }}
//</elementTree>"""
//    }

    /**
     * XML转义字符处理
     */
    private fun String.escapeXml(): String {
        return this.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    /**
     * 发送屏幕信息
     */
    private fun sendScreen() {
        mExecutorService.execute { mClient?.sendXML(currentScreenXML) }
    }

    /**
     * 显示操作列表
     */
    fun showActions() {
        // 因操作弹窗导致不发送屏幕
        xmlPending = false
        mExecutorService.execute { mClient?.getActions() }
    }

    /**
     * 设置操作失败回调
     */
    private fun setActionFailedRunnable(reason: String, delay: Int) {
        mainThreadHandler.removeCallbacks(actionFailedRunnable!!)
        actionFailedRunnable = Runnable {
            Log.e(TAG, reason)
            mExecutorService.execute { mClient?.sendError(reason) }
        }
        mainThreadHandler.postDelayed(actionFailedRunnable!!, delay.toLong())
    }
    /**
     * 启动应用并初始化
     */
    fun launchAppAndInit(packageName: String) {
        Log.d(TAG, "package name: $packageName")
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } else {
            Log.d(TAG, "intent null")
        }
        xmlPending = true
        screenNeedUpdate = true
        firstScreen = true
    }

    /**
     * 重置服务状态
     */
    private fun reset() {
        mClient?.disconnect()
        mClient = null
        xmlPending = false
        screenNeedUpdate = false
        firstScreen = false
        mMobileGPTGlobal = MobileGPTGlobal.reset()
        
        // 停止屏幕更新
        stopPeriodicScreenUpdate()
        
//        mainThreadHandler.post {
//            mSpeech = MobileGPTSpeechRecognizer(this@MobileService)
//            mAskPopUp = AskPopUp(this@MobileService, mClient!!, mSpeech)
//        }
    }



    /**
     * 启动定时屏幕更新功能
     * 每隔1秒调用saveCurrScreen并发送屏幕信息
     */
    private fun startPeriodicScreenUpdate() {
        isScreenUpdateEnabled = true
        screenUpdateRunnable = object : Runnable {
            override fun run() {
                if (isScreenUpdateEnabled) {
                    // 在后台线程执行屏幕保存和发送
                    mExecutorService.execute {
                        saveCurrScreen()
                        sendScreen()
                        Log.d("MobileService", currentScreenXML)
                    }
                    // 1秒后再次执行
                    mainThreadHandler.postDelayed(this, 3000)
                }
            }
        }
        // 启动定时任务
        mainThreadHandler.post(screenUpdateRunnable!!)
        Log.d(TAG, "定时屏幕更新已启动")
    }
    
    /**
     * 停止定时屏幕更新功能
     */
    private fun stopPeriodicScreenUpdate() {
        isScreenUpdateEnabled = false
        screenUpdateRunnable?.let {
            mainThreadHandler.removeCallbacks(it)
        }
        Log.d(TAG, "定时屏幕更新已停止")
    }

    /**
     * 服务关闭
     */
    override fun onDestroy() {
        stopPeriodicScreenUpdate()
        unregisterReceiver(stringReceiver)
        mClient?.disconnect()
        super.onDestroy()
    }

    /**
     * 初始化网络连接
     */
    private fun initNetworkConnection() {
        try {
            Log.d(TAG, "尝试连接服务器: ${MobileGPTGlobal.HOST_IP}:${MobileGPTGlobal.HOST_PORT}")
            mClient = MobileGPTClient(MobileGPTGlobal.HOST_IP, MobileGPTGlobal.HOST_PORT)
            mClient!!.connect()
            mClient!!.receiveMessages(object : MobileGPTClient.OnMessageReceived {
                override fun onReceived(message: String) {
                    Thread {
                        if (message != null) {
                            handleResponse(message)
                        }
                    }.start()
                }
            })
            Log.d(TAG, "成功连接到服务器")
        } catch (e: IOException) {
            Log.e(TAG, "服务器连接失败: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "网络连接初始化过程中发生未知错误: ${e.message}", e)
        }
    }
}