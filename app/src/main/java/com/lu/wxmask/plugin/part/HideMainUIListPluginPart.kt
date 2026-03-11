package com.lu.wxmask.plugin.part

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ListAdapter
import android.widget.ListView
import android.widget.TextView
import com.lu.lposed.api2.XC_MethodHook2
import com.lu.lposed.api2.XposedHelpers2
import com.lu.lposed.plugin.IPlugin
import com.lu.magic.util.ResUtil
import com.lu.magic.util.log.LogUtil
import com.lu.wxmask.ClazzN
import com.lu.wxmask.Constrant
import com.lu.wxmask.MainHook
import com.lu.wxmask.plugin.WXMaskPlugin
import com.lu.wxmask.util.AppVersionUtil
import com.lu.wxmask.util.ConfigUtil
import com.lu.wxmask.util.ext.getViewId
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/**
 * 主页UI处理：彻底解决闪烁问题与完美实现零像素完全隐藏
 */
class HideMainUIListPluginPart : IPlugin {

    companion object {
        val nameViewMap = WeakHashMap<View, CharSequence>()
        val msgViewMap = WeakHashMap<View, CharSequence>()
        val unreadViewMap = WeakHashMap<View, Boolean>()
        
        // 【终极修复】：缓存原生的 UI 和 点击事件，防止被永久破坏
        val originalPaddingMap = WeakHashMap<View, IntArray>()
        val originalBgMap = WeakHashMap<View, Drawable?>()
        val originalClickMap = WeakHashMap<View, View.OnClickListener?>()
        val originalClickableMap = WeakHashMap<View, Boolean>()
        
        var isInterceptorHooked = false
        private val mainHandler = Handler(Looper.getMainLooper())
    }

    // 自定义点击事件类，用于识别是否是我们自己的假事件
    class MaskClickListener(val targetId: String, val context: Context) : View.OnClickListener {
        override fun onClick(v: View?) {
            try {
                val intent = Intent()
                intent.setClassName(context, "com.tencent.mm.ui.chatting.ChattingUI")
                intent.putExtra("Chat_User", targetId)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                context.startActivity(intent)
            } catch (e: Throwable) {
                LogUtil.w("点击拦截跳转失败", e)
            }
        }
    }

    // 利用反射获取 View 当前绑定的原生点击事件
    private fun getOnClickListener(view: View): View.OnClickListener? {
        try {
            val getListenerInfo = View::class.java.getDeclaredMethod("getListenerInfo")
            getListenerInfo.isAccessible = true
            val listenerInfo = getListenerInfo.invoke(view) ?: return null
            val mOnClickListener = listenerInfo.javaClass.getDeclaredField("mOnClickListener")
            mOnClickListener.isAccessible = true
            return mOnClickListener.get(listenerInfo) as? View.OnClickListener
        } catch (e: Throwable) {
            return null
        }
    }

    private fun getAutoTarget(realWxid: String): Pair<String, String> {
        val officialAccounts = listOf(
            Pair("weixin", "微信团队"),
            Pair("officialaccounts", "订阅号消息"),
            Pair("gh_43f2581f6fd6", "微信运动"),
            Pair("filehelper", "文件传输助手"),
            Pair("wxpayapp", "微信支付"),
            Pair("notifymessage", "服务通知")
        )
        val index = Math.abs(realWxid.hashCode()) % officialAccounts.size
        return officialAccounts[index]
    }

    val GetItemMethodName = when (AppVersionUtil.getVersionCode()) {
        Constrant.WX_CODE_8_0_22 -> "aCW"
        in Constrant.WX_CODE_8_0_22..Constrant.WX_CODE_8_0_43 -> "k" 
        Constrant.WX_CODE_PLAY_8_0_48 -> "l"
        Constrant.WX_CODE_8_0_49, Constrant.WX_CODE_8_0_51, Constrant.WX_CODE_8_0_56 -> "l"
        Constrant.WX_CODE_8_0_50 -> "n"
        Constrant.WX_CODE_8_0_53 -> "m"
        else -> "m"
    }

    override fun handleHook(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!isInterceptorHooked) {
            isInterceptorHooked = true
            hookUIInterceptors(context)
        }

        runCatching {
            handleMainUIChattingListView2(context, lpparam)
        }.onFailure {
            LogUtil.w("hide mainUI listview fail, try to old function.")
            handleMainUIChattingListView(context, lpparam)
        }
    }

    private fun hookUIInterceptors(context: Context) {
        val clazzNoMeasuredTextView = ClazzN.from("com.tencent.mm.ui.base.NoMeasuredTextView", context.classLoader)
        if (clazzNoMeasuredTextView != null) {
            XposedHelpers2.findAndHookMethod(clazzNoMeasuredTextView, "setText", CharSequence::class.java, object : XC_MethodHook2() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as View
                    if (nameViewMap.containsKey(view)) param.args[0] = nameViewMap[view]
                    else if (msgViewMap.containsKey(view)) param.args[0] = msgViewMap[view]
                }
            })
        }
        
        XposedHelpers2.findAndHookMethod(TextView::class.java, "setText", CharSequence::class.java, object : XC_MethodHook2() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as View
                if (nameViewMap.containsKey(view)) param.args[0] = nameViewMap[view]
                else if (msgViewMap.containsKey(view)) param.args[0] = msgViewMap[view]
            }
        })

        XposedHelpers2.findAndHookMethod(TextView::class.java, "setText", CharSequence::class.java, TextView.BufferType::class.java, object : XC_MethodHook2() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as View
                if (nameViewMap.containsKey(view)) param.args[0] = nameViewMap[view]
                else if (msgViewMap.containsKey(view)) param.args[0] = msgViewMap[view]
            }
        })

        XposedHelpers2.findAndHookMethod(View::class.java, "setVisibility", Integer.TYPE, object : XC_MethodHook2() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as View
                if (unreadViewMap.containsKey(view)) param.args[0] = View.INVISIBLE
            }
        })
    }

    private fun handleMainUIChattingListView(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        val adapterName = when (AppVersionUtil.getVersionCode()) {
            Constrant.WX_CODE_8_0_22 -> "com.tencent.mm.ui.conversation.k"
            in Constrant.WX_CODE_8_0_32..Constrant.WX_CODE_8_0_34 -> {
                if (AppVersionUtil.getVersionName() == "8.0.35") "com.tencent.mm.ui.conversation.r" else "com.tencent.mm.ui.conversation.p"
            }
            Constrant.WX_CODE_8_0_35 -> "com.tencent.mm.ui.conversation.r"
            in Constrant.WX_CODE_8_0_35..Constrant.WX_CODE_8_0_41 -> "com.tencent.mm.ui.conversation.x" 
            Constrant.WX_CODE_8_0_47 -> "com.tencent.mm.ui.conversation.p3"
            Constrant.WX_CODE_8_0_50 -> "com.tencent.mm.ui.conversation.q3"
            else -> null
        }
        var adapterClazz: Class<*>? = null
        if (adapterName != null) {
            adapterClazz = ClazzN.from(adapterName, context.classLoader)
        }
        if (adapterClazz != null) {
            hookListViewAdapter(adapterClazz)
        } else {
            val setAdapterMethod = XposedHelpers2.findMethodExactIfExists(
                ListView::class.java.name,
                context.classLoader,
                "setAdapter",
                ListAdapter::class.java
            )
            if (setAdapterMethod == null) return
            XposedHelpers2.hookMethod(setAdapterMethod, object : XC_MethodHook2() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val adapter = param.args[0] ?: return
                    if (adapter::class.java.name.startsWith("com.tencent.mm.ui.conversation")) {
                        hookListViewAdapter(adapter.javaClass)
                    }
                }
            })
        }
    }

    private fun handleMainUIChattingListView2(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        val adapterClazzName = when (AppVersionUtil.getVersionCode()) {
            Constrant.WX_CODE_8_0_22 -> "com.tencent.mm.ui.g"
            in Constrant.WX_CODE_8_0_32..Constrant.WX_CODE_8_0_34 -> "com.tencent.mm.ui.y"
            in Constrant.WX_CODE_8_0_35..Constrant.WX_CODE_8_0_38 -> "com.tencent.mm.ui.z"
            in Constrant.WX_CODE_8_0_40..Constrant.WX_CODE_8_0_43 -> "com.tencent.mm.ui.b0"
            in Constrant.WX_CODE_8_0_43..Constrant.WX_CODE_8_0_44 -> "com.tencent.mm.ui.h3"
            in Constrant.WX_CODE_8_0_43..Constrant.WX_CODE_8_0_47,
            Constrant.WX_CODE_PLAY_8_0_48, Constrant.WX_CODE_8_0_50, Constrant.WX_CODE_8_0_51, Constrant.WX_CODE_8_0_53, Constrant.WX_CODE_8_0_56 -> "com.tencent.mm.ui.i3"
            in Constrant.WX_CODE_8_0_58..Constrant.WX_CODE_8_0_60 -> "com.tencent.mm.ui.k3"
            Constrant.WX_CODE_8_0_69 -> "o75.v0" 
            else -> null
        }
        var adapterClazz = if (adapterClazzName != null) ClazzN.from(adapterClazzName) else null

        if (adapterClazz != null) {
            var getItemMethod = findGetItemMethod(adapterClazz)
            if (getItemMethod != null) hookListViewGetItem(getItemMethod)
            hookListViewAdapter(adapterClazz)
            return
        }

        XposedHelpers2.findAndHookMethod(ListView::class.java, "setAdapter", ListAdapter::class.java, object : XC_MethodHook2() {
            private var isHookGetItemMethod = false
            override fun afterHookedMethod(param: MethodHookParam) {
                val adapter = param.args[0] ?: return
                if (adapter::class.java.name.startsWith("com.tencent.mm.ui.conversation")) {
                    if (isHookGetItemMethod) return
                    var getItemMethod = findGetItemMethod(adapter::class.java.superclass)
                    if (getItemMethod == null) {
                        getItemMethod = XposedHelpers2.findMethodExactIfExists(adapter::class.java.superclass, "getItem", Integer.TYPE)
                    }
                    if (getItemMethod != null) {
                        hookListViewGetItem(getItemMethod)
                        isHookGetItemMethod = true
                    }
                }
            }
        })
    }

    private fun hookListViewGetItem(getItemMethod: Method) {
        XposedHelpers2.hookMethod(getItemMethod, object : XC_MethodHook2() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val itemData: Any = param.result ?: return
                val chatUser: String? = XposedHelpers2.getObjectField(itemData, "field_username") as? String
                if (chatUser == null) return

                if (WXMaskPlugin.containChatUser(chatUser)) {
                    val option = ConfigUtil.getOptionData()
                    XposedHelpers2.setObjectField(itemData, "field_content", "")
                    XposedHelpers2.setObjectField(itemData, "field_digest", "")
                    XposedHelpers2.setObjectField(itemData, "field_unReadCount", 0)
                    XposedHelpers2.setObjectField(itemData, "field_UnReadInvite", 0)
                    XposedHelpers2.setObjectField(itemData, "field_unReadMuteCount", 0)
                    XposedHelpers2.setObjectField(itemData, "field_msgType", "1")

                    if (option.enableTravelTime && option.travelTime != 0L) {
                        val cTime = XposedHelpers2.getObjectField<Any>(itemData, "field_conversationTime")
                        if (cTime is Long) {
                            XposedHelpers2.setObjectField(itemData, "field_conversationTime", cTime - option.travelTime)
                        }
                    }
                }
            }
        })
    }

    private fun cleanRecycledViewHooks(view: View) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) cleanRecycledViewHooks(view.getChildAt(i))
        }
        nameViewMap.remove(view)
        msgViewMap.remove(view)
        unreadViewMap.remove(view)
    }

    private fun hookListViewAdapter(adapterClazz: Class<*>) {
        var getViewMethod: Method? = null
        var currentClass: Class<*>? = adapterClazz
        while (currentClass != null && currentClass != Any::class.java) {
            getViewMethod = XposedHelpers2.findMethodExactIfExists(currentClass, "getView", java.lang.Integer.TYPE, View::class.java, ViewGroup::class.java)
            if (getViewMethod != null) break
            currentClass = currentClass.superclass
        }

        if (getViewMethod == null) return

        val getViewMethodIDText = getViewMethod.toString()
        if (MainHook.uniqueMetaStore.contains(getViewMethodIDText)) return
        
        XposedHelpers2.hookMethod(getViewMethod, object : XC_MethodHook2() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val convertView = param.args[1] as? View
                if (convertView != null) cleanRecycledViewHooks(convertView)

                val adapter = param.thisObject as ListAdapter
                val position = (param.args[0] as? Int?) ?: return
                val itemData = adapter.getItem(position) ?: return
                val chatUser = XposedHelpers2.getObjectField<Any>(itemData, "field_username") as? String ?: return

                if (WXMaskPlugin.containChatUser(chatUser)) {
                    val maskBean = WXMaskPlugin.getMaskBeamById(chatUser)
                    if (maskBean != null) {
                        param.setObjectExtra("real_wxid", chatUser)
                        val targetId = if (maskBean.mapId == "hide_completely") "filehelper" 
                        else if (maskBean.mapId.isNullOrBlank()) getAutoTarget(chatUser).first 
                        else maskBean.mapId
                        XposedHelpers2.setObjectField(itemData, "field_username", targetId)
                    }
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val adapter: ListAdapter = param.thisObject as ListAdapter
                val position: Int = (param.args[0] as? Int?) ?: return
                val itemData: Any = adapter.getItem(position) ?: return
                
                val realWxid = param.getObjectExtra("real_wxid") as? String

                // ===== 最高优先级：无条件恢复底层数据的真实 ID =====
                if (realWxid != null) {
                    XposedHelpers2.setObjectField(itemData, "field_username", realWxid)
                }

                if (param.hasThrowable()) return
                val itemView: View = param.result as? View ?: return 
                
                // ===== 【事件缓存】在进行任何破坏性操作前，记录原装点击事件 ====
                val currentListener = getOnClickListener(itemView)
                if (currentListener !is MaskClickListener && !originalClickMap.containsKey(itemView)) {
                    originalClickMap[itemView] = currentListener
                    originalClickableMap[itemView] = itemView.isClickable
                }

                // ===== 处理非隐藏对象（普通好友或被复用的 View） =====
                if (realWxid == null) {
                    val lp = itemView.layoutParams
                    if (lp != null && (lp.height == 0 || lp.width == 0)) {
                        itemView.visibility = View.VISIBLE
                        lp.height = AbsListView.LayoutParams.WRAP_CONTENT
                        lp.width = AbsListView.LayoutParams.MATCH_PARENT
                        itemView.layoutParams = lp
                        
                        if (itemView is ViewGroup) {
                            for (i in 0 until itemView.childCount) itemView.getChildAt(i).visibility = View.VISIBLE
                        }
                    }
                    
                    // 【精细修复】：完美还原原生 UI 属性（消除无灰线后遗症）
                    if (originalPaddingMap.containsKey(itemView)) {
                        val p = originalPaddingMap[itemView]!!
                        itemView.setPadding(p[0], p[1], p[2], p[3])
                    }
                    if (originalBgMap.containsKey(itemView)) {
                        itemView.background = originalBgMap[itemView]
                    }
                    
                    // 【精细修复】：将原装点击事件还给普通好友
                    if (originalClickMap.containsKey(itemView)) {
                        itemView.setOnClickListener(originalClickMap[itemView])
                        itemView.isClickable = originalClickableMap[itemView] ?: true
                    }

                    cleanRecycledViewHooks(itemView)
                    return
                }

                // ================= 处理目标对象 =================
                val maskBean = WXMaskPlugin.getMaskBeamById(realWxid)
                val isCompletelyHide = maskBean?.mapId == "hide_completely"

                if (isCompletelyHide) {
                    itemView.visibility = View.GONE
                    val hideParams = itemView.layoutParams ?: AbsListView.LayoutParams(-1, -2)
                    hideParams.height = 0
                    hideParams.width = 0
                    itemView.layoutParams = hideParams
                    
                    if (itemView is ViewGroup) {
                        for (i in 0 until itemView.childCount) itemView.getChildAt(i).visibility = View.GONE
                    }
                    
                    // 隐藏前缓存原生属性
                    if (!originalPaddingMap.containsKey(itemView)) {
                        originalPaddingMap[itemView] = intArrayOf(itemView.paddingLeft, itemView.paddingTop, itemView.paddingRight, itemView.paddingBottom)
                    }
                    if (!originalBgMap.containsKey(itemView)) {
                        originalBgMap[itemView] = itemView.background
                    }
                    
                    itemView.setBackgroundColor(0x00000000)
                    itemView.setPadding(0, 0, 0, 0)
                    
                    // 屏蔽完全隐藏项的点击事件
                    itemView.setOnClickListener(null)
                    itemView.isClickable = false
                    
                    cleanRecycledViewHooks(itemView)
                    return 
                }

                val nameViewId = ResUtil.getViewId(when (AppVersionUtil.getVersionCode()) {
                    Constrant.WX_CODE_8_0_69 -> "kbq" 
                    else -> "kbq" 
                })
                val nameTv: View? = if (nameViewId != 0) itemView.findViewById(nameViewId) else null

                val msgTvIdName = when (AppVersionUtil.getVersionCode()) {
                    in 0..Constrant.WX_CODE_8_0_22 -> "last_msg_tv"
                    in Constrant.WX_CODE_8_0_22..Constrant.WX_CODE_8_0_40 -> "fhs"
                    Constrant.WX_CODE_PLAY_8_0_42 -> "i2_"
                    Constrant.WX_CODE_8_0_41 -> "ht5"
                    else -> "ht5" 
                }
                val msgViewId = ResUtil.getViewId(msgTvIdName)
                val msgTv: View? = if (msgViewId != 0) itemView.findViewById(msgViewId) else null

                val tipTvIdTextID = when (AppVersionUtil.getVersionCode()) {
                    in 0..Constrant.WX_CODE_8_0_22 -> "tipcnt_tv"
                    Constrant.WX_CODE_PLAY_8_0_42 -> "oqu"
                    in Constrant.WX_CODE_8_0_22..Constrant.WX_CODE_8_0_41 -> "kmv"
                    else -> "kmv"
                }
                val tipTvId = ResUtil.getViewId(tipTvIdTextID)
                val tipTv: View? = if (tipTvId != 0) itemView.findViewById(tipTvId) else null

                val small_red = when (AppVersionUtil.getVersionCode()) {
                    in 0..Constrant.WX_CODE_8_0_40 -> "a2f"
                    Constrant.WX_CODE_PLAY_8_0_42 -> "a_w"
                    Constrant.WX_CODE_8_0_41 -> "o_u"
                    else -> "o_u"
                }
                val dotViewId = ResUtil.getViewId(small_red)
                val dotTv: View? = if (dotViewId != 0) itemView.findViewById(dotViewId) else null

                if (maskBean != null && nameTv != null) {
                    val customName = if (maskBean.tagName.isNullOrBlank() && maskBean.mapId.isNullOrBlank()) {
                        getAutoTarget(realWxid).second
                    } else if (!maskBean.tagName.isNullOrBlank()) {
                        maskBean.tagName
                    } else {
                        "文件传输助手"
                    }
                    nameViewMap[nameTv] = customName
                    
                    if (nameTv is TextView) nameTv.text = customName
                    else XposedHelpers2.callMethod<Any?>(nameTv, "setText", customName)
                    
                    mainHandler.post {
                        if (nameViewMap[nameTv] == customName) {
                            if (nameTv is TextView) nameTv.text = customName
                            else XposedHelpers2.callMethod<Any?>(nameTv, "setText", customName)
                        }
                    }
                }
                
                if (msgTv != null) {
                    msgViewMap[msgTv] = ""
                    if (msgTv is TextView) msgTv.text = ""
                    else XposedHelpers2.callMethod<Any?>(msgTv, "setText", "")
                    
                    mainHandler.post {
                        if (msgViewMap[msgTv] == "") {
                            if (msgTv is TextView) msgTv.text = ""
                            else XposedHelpers2.callMethod<Any?>(msgTv, "setText", "")
                        }
                    }
                }
                
                if (tipTv != null) {
                    unreadViewMap[tipTv] = true
                    tipTv.visibility = View.INVISIBLE
                }
                
                if (dotTv != null) {
                    unreadViewMap[dotTv] = true
                    dotTv.visibility = View.INVISIBLE
                }

                // 注入我们的变脸点击事件
                val targetId = if (maskBean?.mapId.isNullOrBlank()) getAutoTarget(realWxid).first else maskBean!!.mapId
                itemView.setOnClickListener(MaskClickListener(targetId, itemView.context))
            }
        })
        MainHook.uniqueMetaStore.add(getViewMethodIDText)
    }

    private fun findGetItemMethod(adapterClazz: Class<*>?): Method? {
        if (adapterClazz == null) return null
        var method: Method? = XposedHelpers2.findMethodExactIfExists(adapterClazz, GetItemMethodName, Integer.TYPE)
        if (method != null) return method
        
        var methods = XposedHelpers2.findMethodsByExactPredicate(adapterClazz) { m ->
            val ret = !arrayOf(
                Object::class.java, String::class.java, Byte::class.java, Short::class.java,
                Long::class.java, Float::class.java, Double::class.java, java.lang.Byte.TYPE,
                java.lang.Short.TYPE, java.lang.Integer.TYPE, java.lang.Long.TYPE,
                java.lang.Float.TYPE, java.lang.Double.TYPE, java.lang.Void.TYPE
            ).contains(m.returnType)
            val paramVail = m.parameterTypes.size == 1 && m.parameterTypes[0] == Integer.TYPE
            return@findMethodsByExactPredicate paramVail && ret && Modifier.isPublic(m.modifiers) && !Modifier.isAbstract(m.modifiers)
        }
        if (methods.size > 0) method = methods[0]
        return method
    }
}
