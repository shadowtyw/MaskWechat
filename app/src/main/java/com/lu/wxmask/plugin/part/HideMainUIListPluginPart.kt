package com.lu.wxmask.plugin.part

import android.content.Context
import android.graphics.Bitmap.Config
import android.view.View
import android.view.ViewGroup
import android.widget.ListAdapter
import android.widget.ListView
import android.widget.TextView
import com.lu.lposed.api2.XC_MethodHook2
import com.lu.lposed.api2.XposedHelpers2
import com.lu.lposed.plugin.IPlugin
import com.lu.magic.util.GsonUtil
import com.lu.magic.util.ResUtil
import com.lu.magic.util.log.LogUtil
import com.lu.magic.util.view.ChildDeepCheck
import com.lu.wxmask.ClazzN
import com.lu.wxmask.Constrant
import com.lu.wxmask.MainHook
import com.lu.wxmask.plugin.WXConfigPlugin
import com.lu.wxmask.plugin.WXMaskPlugin
import com.lu.wxmask.plugin.ui.MaskUtil
import com.lu.wxmask.util.AppVersionUtil
import com.lu.wxmask.util.ConfigUtil
import com.lu.wxmask.util.ext.getViewId
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation

/**
 * 主页UI（即微信底部“微信”Tab选中时所在页面）处理，消息、小红点相关逻辑
 */
class HideMainUIListPluginPart : IPlugin {
    val GetItemMethodName = when (AppVersionUtil.getVersionCode()) {
        Constrant.WX_CODE_8_0_22 -> "aCW"
        in Constrant.WX_CODE_8_0_22..Constrant.WX_CODE_8_0_43 -> "k" // WX_CODE_PLAY_8_0_42 matches
        Constrant.WX_CODE_PLAY_8_0_48 -> "l"
        Constrant.WX_CODE_8_0_49, Constrant.WX_CODE_8_0_51,  Constrant.WX_CODE_8_0_56 -> "l"
        Constrant.WX_CODE_8_0_50 -> "n"
        Constrant.WX_CODE_8_0_53 -> "m"
        else -> "m"
    }

    override fun handleHook(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            handleMainUIChattingListView2(context, lpparam)
        }.onFailure {
            LogUtil.w("hide mainUI listview fail, try to old function.")
            handleMainUIChattingListView(context, lpparam)
        }
    }

    //隐藏指定用户的主页的消息
    private fun handleMainUIChattingListView(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        val adapterName = when (AppVersionUtil.getVersionCode()) {
            Constrant.WX_CODE_8_0_22 -> "com.tencent.mm.ui.conversation.k"
            in Constrant.WX_CODE_8_0_32..Constrant.WX_CODE_8_0_34 -> {
                if (AppVersionUtil.getVersionName() == "8.0.35") {
                    "com.tencent.mm.ui.conversation.r"
                } else {
                    "com.tencent.mm.ui.conversation.p"
                }
            }

            Constrant.WX_CODE_8_0_35 -> "com.tencent.mm.ui.conversation.r"
            in Constrant.WX_CODE_8_0_35..Constrant.WX_CODE_8_0_41 -> "com.tencent.mm.ui.conversation.x" // WX_CODE_PLAY_8_0_42 matches
            Constrant.WX_CODE_8_0_47 -> "com.tencent.mm.ui.conversation.p3"
            Constrant.WX_CODE_8_0_50 -> "com.tencent.mm.ui.conversation.q3"
            else -> null
        }
        var adapterClazz: Class<*>? = null
        if (adapterName != null) {
            adapterClazz = ClazzN.from(adapterName, context.classLoader)
        }
        if (adapterClazz != null) {
            LogUtil.d("WeChat MainUI main Tap List Adapter", adapterClazz)
            hookListViewAdapter(adapterClazz)
        } else {
            LogUtil.w("WeChat MainUI not found Adapter for ListView, guess start.")
            val setAdapterMethod = XposedHelpers2.findMethodExactIfExists(
                ListView::class.java.name,
                context.classLoader,
                "setAdapter",
                ListAdapter::class.java
            )
            if (setAdapterMethod == null) {
                LogUtil.w("setAdapterMethod is null")
                return
            }
            XposedHelpers2.hookMethod(
                setAdapterMethod,
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val adapter = param.args[0] ?: return
                        LogUtil.i("hook List adapter ", adapter)
                        if (adapter::class.java.name.startsWith("com.tencent.mm.ui.conversation")) {
                            LogUtil.w(AppVersionUtil.getSmartVersionName(), "guess adapter: ", adapter)
                            hookListViewAdapter(adapter.javaClass)
                        }
                    }
                }
            )
        }
    }

    private fun hookListViewAdapter(adapterClazz: Class<*>) {
        val getViewMethod: Method = XposedHelpers2.findMethodExactIfExists(
            adapterClazz,
            "getView",
            java.lang.Integer.TYPE,
            View::class.java,
            ViewGroup::class.java
        ) ?: return
        val getViewMethodIDText = getViewMethod.toString()
        if (MainHook.uniqueMetaStore.contains(getViewMethodIDText)) {
            return
        }
        LogUtil.w(getViewMethod)
        val baseConversationClazz = ClazzN.from(ClazzN.BaseConversation)
        
        XposedHelpers2.hookMethod(
            getViewMethod,
            object : XC_MethodHook2() {

                // 【新增：在界面渲染前，临时替换微信ID】
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val adapter = param.thisObject as ListAdapter
                    val position = (param.args[0] as? Int?) ?: return
                    val itemData = adapter.getItem(position) ?: return
                    
                    val chatUser = XposedHelpers2.getObjectField<Any>(itemData, "field_username") as? String ?: return

                    if (WXMaskPlugin.containChatUser(chatUser)) {
                        val option = ConfigUtil.getOptionData()
                        if (option.enableMapConversation) {
                            val maskBean = WXMaskPlugin.getMaskBeamById(chatUser)
                            if (maskBean != null) {
                                // 1. 暂存真实ID
                                param.setObjectExtra("real_wxid", chatUser)
                                
                                // 2. 临时替换成替身ID，欺骗微信加载替身头像
                                XposedHelpers2.setObjectField(itemData, "field_username", maskBean.mapId)
                            }
                        }
                    }
                }

                // 【修改：界面渲染后，恢复真实ID，防止产生分身】
                override fun afterHookedMethod(param: MethodHookParam) {
                    val adapter: ListAdapter = param.thisObject as ListAdapter
                    val position: Int = (param.args[0] as? Int?) ?: return
                    val itemData: Any = adapter.getItem(position) ?: return

                    val itemView: View = param.args[1] as? View ?: return
                    
                    val realWxid = param.getObjectExtra("real_wxid") as? String

                    if (realWxid != null) {
                        // 恢复真实的微信ID，避免脏数据导致列表错乱和分身
                        XposedHelpers2.setObjectField(itemData, "field_username", realWxid)

                        // 执行UI隐藏逻辑（去红点、清空最后一条消息）
                        hideUnReadTipView(itemView, param)
                        hideMsgViewItemText(itemView, param)
                        
                    } else {
                        // 兼容不需要变脸、只需要隐藏消息的情况
                        val chatUser = XposedHelpers2.getObjectField<Any>(itemData, "field_username") as? String ?: return
                        if (WXMaskPlugin.containChatUser(chatUser)) {
                            hideUnReadTipView(itemView, param)
                            hideMsgViewItemText(itemView, param)
                        }
                    }
                }

                private fun hideLastMsgTime(itemView: View, params: MethodHookParam) {
                    val viewId = ResUtil.getViewId("l0s")
                    itemView.findViewById<View>(viewId)?.visibility = View.INVISIBLE
                }

                private fun hideUnReadTipView(itemView: View, param: MethodHookParam) {
                    val tipTvIdTextID = when (AppVersionUtil.getVersionCode()) {
                        in 0..Constrant.WX_CODE_8_0_22 -> "tipcnt_tv"
                        Constrant.WX_CODE_PLAY_8_0_42 -> "oqu"
                        in Constrant.WX_CODE_8_0_22..Constrant.WX_CODE_8_0_41 -> "kmv"
                        else -> "kmv"
                    }
                    val tipTvId = ResUtil.getViewId(tipTvIdTextID)
                    itemView.findViewById<View>(tipTvId)?.visibility = View.INVISIBLE

                    val small_red = when (AppVersionUtil.getVersionCode()) {
                        in 0..Constrant.WX_CODE_8_0_40 -> "a2f"
                        Constrant.WX_CODE_PLAY_8_0_42 -> "a_w"
                        Constrant.WX_CODE_8_0_41 -> "o_u"
                        else -> "o_u"
                    }
                    val viewId = ResUtil.getViewId(small_red)
                    itemView.findViewById<View>(viewId)?.visibility = View.INVISIBLE
                }

                private fun hideMsgViewItemText(itemView: View, param: MethodHookParam) {
                    val msgTvIdName = when (AppVersionUtil.getVersionCode()) {
                        in 0..Constrant.WX_CODE_8_0_22 -> "last_msg_tv"
                        in Constrant.WX_CODE_8_0_22..Constrant.WX_CODE_8_0_40 -> "fhs"
                        Constrant.WX_CODE_PLAY_8_0_42 -> "i2_"
                        Constrant.WX_CODE_8_0_41 -> "ht5"
                        else -> "ht5"
                    }
                    val lastMsgViewId = ResUtil.getViewId(msgTvIdName)
                    if (lastMsgViewId != 0 && lastMsgViewId != View.NO_ID) {
                        try {
                            val msgTv: View? = itemView.findViewById(lastMsgViewId)
                            XposedHelpers2.callMethod<Any?>(msgTv, "setText", "")
                        } catch (e: Throwable) {
                            LogUtil.w("error", e)
                        }
                    } else {
                        val ClazzNoMeasuredTextView = ClazzN.from("com.tencent.mm.ui.base.NoMeasuredTextView")
                        ChildDeepCheck().each(itemView) { child ->
                            try {
                                if (ClazzNoMeasuredTextView?.isAssignableFrom(child::class.java) == true
                                    || TextView::class.java.isAssignableFrom(child::class.java)
                                ) {
                                    XposedHelpers2.callMethod<String?>(child, "setText", "")
                                }
                            } catch (e: Throwable) {
                            }
                        }
                    }
                }
            })
        MainHook.uniqueMetaStore.add(getViewMethodIDText)
    }

    private fun findGetItemMethod(adapterClazz: Class<*>?): Method? {
        if (adapterClazz == null) {
            return null
        }
        var method: Method? = XposedHelpers2.findMethodExactIfExists(adapterClazz, GetItemMethodName, Integer.TYPE)
        if (method != null) {
            return method
        }
        var methods = XposedHelpers2.findMethodsByExactPredicate(adapterClazz) { m ->
            val ret = !arrayOf(
                Object::class.java,
                String::class.java,
                Byte::class.java,
                Short::class.java,
                Long::class.java,
                Float::class.java,
                Double::class.java,
                String::class.java,
                java.lang.Byte.TYPE,
                java.lang.Short.TYPE,
                java.lang.Integer.TYPE,
                java.lang.Long.TYPE,
                java.lang.Float.TYPE,
                java.lang.Double.TYPE,
                java.lang.Void.TYPE
            ).contains(m.returnType)
            val paramVail = m.parameterTypes.size == 1 && m.parameterTypes[0] == Integer.TYPE
            return@findMethodsByExactPredicate paramVail && ret && Modifier.isPublic(m.modifiers) && !Modifier.isAbstract(m.modifiers)
        }
        if (methods.size > 0) {
            method = methods[0]
            if (methods.size > 1) {
                LogUtil.d("find getItem methods: []--> " + methods.joinToString("\n"))
            }
            LogUtil.d("guess getItem method $method")
        }
        return method
    }

    private fun handleMainUIChattingListView2(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        val adapterClazzName = when (AppVersionUtil.getVersionCode()) {
            Constrant.WX_CODE_8_0_22 -> "com.tencent.mm.ui.g"
            in Constrant.WX_CODE_8_0_32..Constrant.WX_CODE_8_0_34 -> "com.tencent.mm.ui.y"
            in Constrant.WX_CODE_8_0_35..Constrant.WX_CODE_8_0_38 -> "com.tencent.mm.ui.z"
            in Constrant.WX_CODE_8_0_40..Constrant.WX_CODE_8_0_43 -> "com.tencent.mm.ui.b0" // WX_CODE_PLAY_8_0_42 matches
            in Constrant.WX_CODE_8_0_43..Constrant.WX_CODE_8_0_44 -> "com.tencent.mm.ui.h3"
            in Constrant.WX_CODE_8_0_43..Constrant.WX_CODE_8_0_47,
            Constrant.WX_CODE_PLAY_8_0_48, Constrant.WX_CODE_8_0_50, Constrant.WX_CODE_8_0_51, Constrant.WX_CODE_8_0_53, Constrant.WX_CODE_8_0_56,-> "com.tencent.mm.ui.i3"
            in Constrant.WX_CODE_8_0_58..Constrant.WX_CODE_8_0_60 -> "com.tencent.mm.ui.k3"
            Constrant.WX_CODE_8_0_69 -> "o75.v0" // 这里保留了你加入的8.0.69适配
            else -> null
        }
        var getItemMethod = if (adapterClazzName != null) {
            findGetItemMethod(ClazzN.from(adapterClazzName))
        } else {
            null
        }
        if (getItemMethod != null) {
            hookListViewGetItem(getItemMethod)
            return
        }

        LogUtil.w("WeChat MainUI ListView not found adapter, guess start.")
        XposedHelpers2.findAndHookMethod(
            ListView::class.java,
            "setAdapter",
            ListAdapter::class.java,
            object : XC_MethodHook2() {
                private var isHookGetItemMethod = false

                override fun afterHookedMethod(param: MethodHookParam) {
                    val adapter = param.args[0] ?: return
                    LogUtil.d("List adapter ", adapter)
                    if (adapter::class.java.name.startsWith("com.tencent.mm.ui.conversation")) {
                        if (isHookGetItemMethod) {
                            return
                        }
                        LogUtil.w(AppVersionUtil.getSmartVersionName(), "guess setAdapter: ", adapter, adapter.javaClass.superclass)
                        var getItemMethod = findGetItemMethod(adapter::class.java.superclass)
                        if (getItemMethod == null) {
                            getItemMethod = XposedHelpers2.findMethodExactIfExists(adapter::class.java.superclass, "getItem", Integer.TYPE)
                        }
                        if (getItemMethod != null) {
                            hookListViewGetItem(getItemMethod)
                            isHookGetItemMethod = true
                        } else {
                            LogUtil.w("guess getItem method is ", getItemMethod)
                        }
                    }
                }
            }
        )
    }

    private fun hookListViewGetItem(getItemMethod: Method) {
        LogUtil.d(">>>>>>>>>>.", getItemMethod)

        XposedHelpers2.hookMethod(
            getItemMethod,
            object : XC_MethodHook2() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val itemData: Any = param.result ?: return
                    val chatUser: String? = XposedHelpers2.getObjectField(itemData, "field_username")
                    if (chatUser == null) {
                        return
                    }
                    if (WXMaskPlugin.containChatUser(chatUser)) {
                        
                        // 【终极扫描雷达开启！】
                        // 直接把底层数据里所有的字符串扒光打印出来
                        val fields = itemData.javaClass.fields + itemData.javaClass.declaredFields
                        for (field in fields) {
                            try {
                                field.isAccessible = true
                                val value = field.get(itemData)
                                if (value is String && value.isNotEmpty()) {
                                    // 用 .w 警告级别打印，保证绝对能在日志里显示出来
                                    LogUtil.w("【终极雷达】字段名: ${field.name} ---> 里面的值: $value")
                                }
                            } catch (e: Exception) { }
                        }

                        // 下面保留清理消息和红点的功能，原先导致分身的代码已被移除
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
            }
        )
    }
}
