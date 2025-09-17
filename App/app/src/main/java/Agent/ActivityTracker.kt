package Agent

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Activity跟踪器，用于监听Activity生命周期变化
 */
object ActivityTracker {
    private var currentActivity: Activity? = null
    private var activityChangeListener: ActivityChangeListener? = null

    /**
     * Activity变化监听接口
     */
    interface ActivityChangeListener {
        /**
         * 当Activity发生变化时调用
         * @param newActivity 新的Activity实例
         * @param oldActivity 旧的Activity实例（可能为null）
         */
        fun onActivityChanged(newActivity: Activity?, oldActivity: Activity?)
    }

    /**
     * 注册Application生命周期回调
     * @param application Application实例
     */
    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                val oldActivity = currentActivity
                currentActivity = activity
                // 通知Activity变化
                if (oldActivity != activity) {
                    activityChangeListener?.onActivityChanged(activity, oldActivity)
                }
            }
            override fun onActivityPaused(activity: Activity) {
                if (currentActivity == activity) {
                    val oldActivity = currentActivity
                    currentActivity = null
                    // 通知Activity变化
                    activityChangeListener?.onActivityChanged(null, oldActivity)
                }
            }
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * 获取当前Activity
     * @return 当前Activity实例，可能为null
     */
    fun getCurrentActivity(): Activity? {
        return currentActivity
    }

    /**
     * 设置Activity变化监听器
     * @param listener Activity变化监听器
     */
    fun setActivityChangeListener(listener: ActivityChangeListener?) {
        activityChangeListener = listener
    }
}