package com.lu.wxmask.plugin.part

import android.content.Context
import android.content.Intent
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
        // 【终极武器】利用 WeakHashMap 记录需要被强行篡改的 View。
        // 彻底无视微信的任何异步加载，只要走到 setText，就会被强制接管！
        val nameViewMap = WeakHashMap<View, CharSequence>()
        val msgViewMap = WeakHashMap<View, CharSequence>()
        val unreadViewMap = WeakHashMap<View, Boolean>()
        var isInterceptorHooked = false
    }

    // 全自动官方号盲盒系统
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
        // 初始化底层 UI 拦截器
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

    // =====================================================================
    // 【核心黑科技】：挂载全局 setText 和 setVisibility 拦截器
    // 任何微信自带的异步线程想要恢复真实名字或显示红点，都会在这里被直接拦截改掉
    // =====================================================================
    private fun hookUIInterceptors(context: Context) {
        // 1. 拦截微信自定义的 NoMeasuredTextView (主页名字和消息主要用这个)
        val clazzNoMeasuredTextView = ClazzN.from("com.tencent.mm.ui.base.NoMeasuredTextView", context.classLoader)
        if (clazzNoMeasuredTextView != null) {
            XposedHelpers2.findAndHookMethod(clazzNoMeasuredTextView, "setText", CharSequence::class.java, object : XC_MethodHook2() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as View
                    if (nameViewMap.containsKey(view)) {
                        param.args[0] = nameViewMap[view]
                    } else if (msgViewMap.containsKey(view)) {
                        param.args[0] = msgViewMap[view]
                    }
                }
            })
        }
        
        // 2. 拦截普通的 TextView 兜底
        XposedHelpers2.findAndHookMethod(TextView::class.java, "setText", CharSequence::class.java, TextView.BufferType::class.java, object : XC_MethodHook2() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as View
                if (nameViewMap.containsKey(view)) {
                    param.args[0] = nameViewMap[view]
                } else if (msgViewMap.containsKey(view)) {
                    param.args[0] = msgViewMap[view]
                }
            }
        })

        // 3. 拦截红点和提示的显示状态
        XposedHelpers2.findAndHookMethod(View::class.java, "setVisibility", Integer.TYPE, object : XC_MethodHook2() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val view = param.thisObject as View
                if (unreadViewMap.containsKey(view)) {
                    param.args[0] = View.INVISIBLE // 永远按死在不可见状态
                }
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

    // 新增一个辅助方法，用于递归清理被复用 View 的拦截标记
    private fun cleanRecycledViewHooks(view: View) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                cleanRecycledViewHooks(view.getChildAt(i))
            }
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
                // ================= 【修复闪烁核心】 =================
                // 拦截复用的 convertView，在微信绑定数据前，彻底清空残留的 Hook 标记
                val convertView = param.args[1] as? View
                if (convertView != null) {
                    // 如果这个 View 是我们下面造出来的“占位空 View”，必须设为 null
                    // 否则微信内部去强转 ViewHolder 时会直接 Crash！
                    if (convertView.tag == "HIDE_COMPLETELY_DUMMY") {
                        param.args[1] = null 
                    } else {
                        // 正常复用的 View，递归清理掉 HashMap 里的残留，防止影响正常好友
                        cleanRecycledViewHooks(convertView)
                    }
                }
                // =================================================

                val adapter = param.thisObject as ListAdapter
                val position = (param.args[0] as? Int?) ?: return
                val itemData = adapter.getItem(position) ?: return
                val chatUser = XposedHelpers2.getObjectField<Any>(itemData, "field_username") as? String ?: return

                if (WXMaskPlugin.containChatUser(chatUser)) {
                    val maskBean = WXMaskPlugin.getMaskBeamById(chatUser)
                    if (maskBean != null) {
                        param.setObjectExtra("real_wxid", chatUser)
                        val targetId = if (maskBean.mapId == "hide_completely") {
                            "filehelper" 
                        } else if (maskBean.mapId.isNullOrBlank()) {
                            getAutoTarget(chatUser).first
                        } else {
                            maskBean.mapId
                        }
                        // 骗取头像加载器
                        XposedHelpers2.setObjectField(itemData, "field_username", targetId)
                    }
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                val adapter: ListAdapter = param.thisObject as ListAdapter
                val position: Int = (param.args[0] as? Int?) ?: return
                val itemData: Any = adapter.getItem(position) ?: return
                val itemView: View = param.args[1] as? View ?: return // 这是原有的 convertView

                val realWxid = param.getObjectExtra("real_wxid") as? String

                // ===== 处理非隐藏对象（如果刚好处在复用周期，恢复状态） =====
                if (realWxid == null) {
                    cleanRecycledViewHooks(itemView) 
                    return
                }

                // ================= 处理目标对象 =================
                XposedHelpers2.setObjectField(itemData, "field_username", realWxid)

                val maskBean = WXMaskPlugin.getMaskBeamById(realWxid)
                val isCompletelyHide = maskBean?.mapId == "hide_completely"

                // ================= 【修复灰线核心】 =================
                if (isCompletelyHide) {
                    // 彻底抛弃微信的复杂 Item 布局，直接返回一个干干净净的空 View
                    val context = (param.args[2] as ViewGroup).context // 从 parent 获取 context 最稳妥
                    val dummyView = View(context)
                    dummyView.layoutParams = AbsListView.LayoutParams(-1, 1) // 高度设为 1px 比 0px 安全，防止某些老版本 ListView 测量崩溃
                    dummyView.visibility = View.GONE
                    dummyView.tag = "HIDE_COMPLETELY_DUMMY" // 打上标记，防复用报错
                    
                    param.result = dummyView // 狸猫换太子！直接替换返回值
                    
                    // 清除原 View 里的拦截，防内存泄漏
                    cleanRecycledViewHooks(itemView)
                    return 
                }
                // =================================================

                // 以下是正常的伪装逻辑 (获取 View ID 等逻辑保持不变)
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

                // 【解决闪烁：注入底层拦截器】
                if (maskBean != null && nameTv != null) {
                    val customName = if (maskBean.tagName.isNullOrBlank() && maskBean.mapId.isNullOrBlank()) {
                        getAutoTarget(realWxid).second
                    } else if (!maskBean.tagName.isNullOrBlank()) {
                        maskBean.tagName
                    } else {
                        "文件传输助手"
                    }
                    nameViewMap[nameTv] = customName
                    XposedHelpers2.callMethod<Any?>(nameTv, "setText", customName)
                }
                
                if (msgTv != null) {
                    msgViewMap[msgTv] = ""
                    XposedHelpers2.callMethod<Any?>(msgTv, "setText", "")
                }
                
                if (tipTv != null) {
                    unreadViewMap[tipTv] = true
                    tipTv.visibility = View.INVISIBLE
                }
                
                if (dotTv != null) {
                    unreadViewMap[dotTv] = true
                    dotTv.visibility = View.INVISIBLE
                }

                // 【保留防乱跳的强制跳转逻辑】
                itemView.setOnClickListener {
                    try {
                        val targetId = if (maskBean?.mapId.isNullOrBlank()) getAutoTarget(realWxid).first else maskBean!!.mapId
                        val intent = Intent()
                        intent.setClassName(itemView.context, "com.tencent.mm.ui.chatting.ChattingUI")
                        intent.putExtra("Chat_User", targetId)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        itemView.context.startActivity(intent)
                    } catch (e: Throwable) {
                        LogUtil.w("点击拦截跳转失败", e)
                    }
                }
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
