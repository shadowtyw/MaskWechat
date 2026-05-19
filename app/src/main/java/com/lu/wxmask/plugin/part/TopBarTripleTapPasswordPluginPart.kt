package com.lu.wxmask.plugin.part

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.lu.lposed.api2.XC_MethodHook2
import com.lu.lposed.api2.XposedHelpers2
import com.lu.lposed.plugin.IPlugin
import com.lu.magic.util.ResUtil
import com.lu.magic.util.log.LogUtil
import com.lu.wxmask.BuildConfig
import com.lu.wxmask.Constrant
import com.lu.wxmask.MainHook
import com.lu.wxmask.plugin.WXMaskPlugin
import com.lu.wxmask.util.AppVersionUtil
import com.lu.wxmask.util.ConfigUtil
import com.lu.wxmask.util.ext.getViewId
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 顶部栏三击密码保护功能
 * 
 * 功能：
 * 1. 三击标题栏触发密码输入对话框
 * 2. 密码验证通过后临时解除隐藏（直到屏幕关闭）
 * 3. 屏幕关闭后自动恢复隐藏状态
 */
class TopBarTripleTapPasswordPluginPart : IPlugin {
    
    companion object {
        private var titleTextView: TextView? = null
        private var lastClickTime = 0L
        private var clickCount = 0
        private const val TRIPLE_CLICK_INTERVAL = 500L // 三击时间间隔（毫秒）
        private var isTemporaryUnhide = false
        private var temporaryUnhideCallback: (() -> Unit)? = null
    }

    override fun handleHook(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        val versionCode = AppVersionUtil.getVersionCode()
        
        if (versionCode >= Constrant.WX_CODE_8_0_70) {
            LogUtil.d("TopBarTripleTapPasswordPluginPart: Using 8.0.70+ pipeline")
            handleWechat8070TitleBar(context, lpparam)
        } else {
            LogUtil.d("TopBarTripleTapPasswordPluginPart: Using legacy pipeline")
            handleLegacyTitleBar(context, lpparam)
        }
        
        // 注册屏幕关闭广播接收器
        registerScreenOffReceiver(context)
    }

    /**
     * 8.0.70+ 标题栏处理
     */
    private fun handleWechat8070TitleBar(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            // Hook LauncherUI (主Activity) 的 onCreate
            val launcherUIClazz = XposedHelpers2.findClass(
                "com.tencent.mm.ui.LauncherUI",
                context.classLoader
            ) ?: return@runCatching
            
            XposedHelpers2.findAndHookMethod(
                launcherUIClazz,
                "onCreate",
                android.os.Bundle::class.java,
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject
                        bindTitleTextView(activity, context)
                    }
                }
            )
            
            // Hook onResume 以重新绑定
            XposedHelpers2.findAndHookMethod(
                launcherUIClazz,
                "onResume",
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject
                        bindTitleTextView(activity, context)
                    }
                }
            )
        }.onFailure {
            LogUtil.w("handleWechat8070TitleBar failed", it)
        }
    }

    /**
     * Legacy 标题栏处理
     */
    private fun handleLegacyTitleBar(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val mainUIClazz = XposedHelpers2.findClass(
                "com.tencent.mm.ui.MainUI",
                context.classLoader
            ) ?: return@runCatching
            
            XposedHelpers2.findAndHookMethod(
                mainUIClazz,
                "onCreate",
                android.os.Bundle::class.java,
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject
                        bindTitleTextView(activity, context)
                    }
                }
            )
        }.onFailure {
            LogUtil.w("handleLegacyTitleBar failed", it)
        }
    }

    /**
     * 绑定标题栏 TextView 点击事件
     */
    private fun bindTitleTextView(activity: Any, context: Context) {
        runCatching {
            val rootView = if (activity is android.app.Activity) {
                activity.window.decorView
            } else {
                XposedHelpers2.getObjectField<View>(activity, "mView") ?: return@runCatching
            }
            
            val titleTv = findTitleTextView(rootView) ?: return@runCatching
            titleTextView = titleTv
            
            // 设置点击监听器
            titleTv.setOnClickListener { v ->
                handleTitleClick(context)
            }
            
            LogUtil.d("TopBarTripleTapPasswordPluginPart: Title TextView bound")
        }.onFailure {
            LogUtil.w("bindTitleTextView failed", it)
        }
    }

    /**
     * 查找标题栏 TextView
     */
    private fun findTitleTextView(view: View): TextView? {
        if (view is TextView) {
            val text = view.text?.toString() ?: ""
            // 微信主标题通常包含"微信"字样
            if (text.contains("微信") || view.id == getWechatTitleId(view.context)) {
                return view
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val result = findTitleTextView(child)
                if (result != null) return result
            }
        }
        return null
    }

    /**
     * 获取微信标题栏资源 ID
     */
    private fun getWechatTitleId(context: Context): Int {
        return runCatching {
            ResUtil.getViewId("main_tab_title")
        }.getOrNull() ?: runCatching {
            ResUtil.getViewId("b5n") // 某版本的标题 ID
        }.getOrNull() ?: View.NO_ID
    }

    /**
     * 处理标题点击（三击检测）
     */
    private fun handleTitleClick(context: Context) {
        val currentTime = System.currentTimeMillis()
        
        if (currentTime - lastClickTime < TRIPLE_CLICK_INTERVAL) {
            clickCount++
            if (clickCount >= 3) {
                clickCount = 0
                onTripleClick(context)
            }
        } else {
            clickCount = 1
        }
        
        lastClickTime = currentTime
    }

    /**
     * 三击事件回调
     */
    private fun onTripleClick(context: Context) {
        LogUtil.d("TopBarTripleTapPasswordPluginPart: Triple click detected")
        
        val option = ConfigUtil.getOptionData()
        if (!option.enableTripleTapPassword) {
            return
        }
        
        showPasswordDialog(context)
    }

    /**
     * 显示密码对话框
     */
    fun showPasswordDialog(context: Context) {
        val option = ConfigUtil.getOptionData()
        val expectedPassword = option.tripleTapPassword
        
        if (expectedPassword.isNullOrBlank()) {
            // 密码未设置，提示用户
            configPassword(context)
            return
        }
        
        val editText = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "请输入密码"
            setPadding(50, 30, 50, 30)
        }
        
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 10)
            addView(editText)
        }
        
        AlertDialog.Builder(context)
            .setTitle("验证密码")
            .setMessage("请输入密码以临时解除隐藏")
            .setView(container)
            .setPositiveButton("确认") { dialog, _ ->
                val inputPassword = editText.text.toString()
                if (inputPassword == expectedPassword) {
                    toggleAllTemporaryUnhide()
                    android.widget.Toast.makeText(context, "已临时解除隐藏", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(context, "密码错误", android.widget.Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    /**
     * 配置密码
     */
    private fun configPassword(context: Context) {
        val editText = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "请设置新密码"
            setPadding(50, 30, 50, 30)
        }
        
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 10)
            addView(editText)
        }
        
        AlertDialog.Builder(context)
            .setTitle("设置密码")
            .setMessage("请设置三击密码保护的密码")
            .setView(container)
            .setPositiveButton("保存") { dialog, _ ->
                val newPassword = editText.text.toString()
                if (newPassword.isNotBlank()) {
                    val option = ConfigUtil.getOptionData()
                    option.tripleTapPassword = newPassword
                    ConfigUtil.setOptionData(option)
                    android.widget.Toast.makeText(context, "密码已设置", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    android.widget.Toast.makeText(context, "密码不能为空", android.widget.Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }

    /**
     * 切换所有临时解除隐藏状态
     */
    private fun toggleAllTemporaryUnhide() {
        isTemporaryUnhide = !isTemporaryUnhide
        
        if (isTemporaryUnhide) {
            // 临时解除所有隐藏
            WXMaskPlugin.temporaryUnhideAll()
            applyHiddenMessageTitleStyle()
        } else {
            // 恢复隐藏
            WXMaskPlugin.restoreAllHidden()
            restoreHiddenMessageTitleStyle()
        }
        
        // 通知刷新
        temporaryUnhideCallback?.invoke()
    }

    /**
     * 清除临时解除隐藏状态
     */
    fun clearTemporaryUnhide() {
        if (isTemporaryUnhide) {
            isTemporaryUnhide = false
            WXMaskPlugin.restoreAllHidden()
            restoreHiddenMessageTitleStyle()
            temporaryUnhideCallback?.invoke()
        }
    }

    /**
     * 应用隐藏消息标题样式
     */
    private fun applyHiddenMessageTitleStyle() {
        titleTextView?.let { tv ->
            tv.post {
                tv.setTextColor(Color.GRAY)
                tv.setTypeface(null, Typeface.ITALIC)
            }
        }
    }

    /**
     * 恢复隐藏消息标题样式
     */
    private fun restoreHiddenMessageTitleStyle() {
        titleTextView?.let { tv ->
            tv.post {
                tv.setTextColor(Color.BLACK)
                tv.setTypeface(null, Typeface.NORMAL)
            }
        }
    }

    /**
     * 设置隐藏消息的未读状态
     */
    fun setHiddenMessageUnread(hasUnread: Boolean) {
        titleTextView?.let { tv ->
            tv.post {
                if (hasUnread) {
                    tv.setTextColor(Color.RED)
                    tv.setTypeface(null, Typeface.BOLD)
                } else {
                    tv.setTextColor(Color.BLACK)
                    tv.setTypeface(null, Typeface.NORMAL)
                }
            }
        }
    }

    /**
     * 注册屏幕关闭广播接收器
     */
    private fun registerScreenOffReceiver(context: Context) {
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                        LogUtil.d("TopBarTripleTapPasswordPluginPart: Screen off, clearing temporary unhide")
                        clearTemporaryUnhide()
                    }
                }
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            
            LogUtil.d("TopBarTripleTapPasswordPluginPart: Screen off receiver registered")
        }.onFailure {
            LogUtil.w("registerScreenOffReceiver failed", it)
        }
    }

    // Getter
    fun getTitleTextView(): TextView? = titleTextView

    // Setter for callback
    fun setTemporaryUnhideCallback(callback: () -> Unit) {
        temporaryUnhideCallback = callback
    }
}
