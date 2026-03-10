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
 * 主页UI处理
 */
class HideMainUIListPluginPart : IPlugin {
    val GetItemMethodName = when (AppVersionUtil.getVersionCode()) {
        Constrant.WX_CODE_8_0_22 -> "aCW"
        in Constrant.WX_CODE_8_0_22..Constrant.WX_CODE_8_0_43 -> "k" 
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
            XposedHelpers2.hookMethod(
                setAdapterMethod,
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val adapter = param.args[0] ?: return
                        if (adapter::class.java.name.startsWith("com.tencent.mm.ui.conversation")) {
                            hookListViewAdapter(adapter.javaClass)
                        }
                    }
                }
            )
        }
    }

    private fun hookListViewAdapter(adapterClazz: Class<*>) {
        // 【关键修复】深度寻找 getView 方法，防止微信把它藏在父类里导致找不到
        var getViewMethod: Method? = null
        var currentClass: Class<*>? = adapterClazz
        while (currentClass != null && currentClass != Any::class.java) {
            getViewMethod = XposedHelpers2.findMethodExactIfExists(
                currentClass,
                "getView",
                java.lang.Integer.TYPE,
                View::class.java,
                ViewGroup::class.java
            )
            if (getViewMethod != null) break
            currentClass = currentClass.superclass
        }

        if (getViewMethod == null) {
            de.robv.android.xposed.XposedBridge.log("【UI雷达】严重错误：在 ${adapterClazz.name} 及其父类中找不到 getView 方法！")
            return
        }

        val getViewMethodIDText = getViewMethod.toString()
        if (MainHook.uniqueMetaStore.contains(getViewMethodIDText)) return
        
        XposedHelpers2.hookMethod(
            getViewMethod,
            object : XC_MethodHook2() {

                var hasPrintedRadar = false

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
                                param.setObjectExtra("real_wxid", chatUser)
                                XposedHelpers2.setObjectField(itemData, "field_username", maskBean.mapId)
                            }
                        }
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val adapter: ListAdapter = param.thisObject as ListAdapter
                    val position: Int = (param.args[0] as? Int?) ?: return
                    val itemData: Any = adapter.getItem(position) ?: return
                    val itemView: View = param.args[1] as? View ?: return
                    
                    // 【全屏无差别UI雷达】
                    if (!hasPrintedRadar) {
                        de.robv.android.xposed.XposedBridge.log("【全屏UI雷达】已启动！开始暴力扫描控件...")
                        ChildDeepCheck().each(itemView) { child ->
                            try {
                                val text = XposedHelpers2.callMethod<Any?>(child, "getText")
                                if (text != null && text.toString().isNotBlank()) {
                                    val id = child.id
                                    var idName = "无ID"
                                    try {
                                        if (id != View.NO_ID) {
                                            idName = child.context.resources.getResourceEntryName(id)
                                        }
                                    } catch (e: Exception) { }
                                    
                                    de.robv.android.xposed.XposedBridge.log("【全屏UI雷达】-> 资源ID名: $idName ---> 文字内容: $text")
                                }
                            } catch (e: Throwable) { }
                        }
                        hasPrintedRadar = true
                    }

                    val realWxid = param.getObjectExtra("real_wxid") as? String

                    if (realWxid != null) {
                        // 换回真实ID
                        XposedHelpers2.setObjectField(itemData, "field_username", realWxid)

                        hideUnReadTipView(itemView, param)
                        hideMsgViewItemText(itemView, param)
                        
                    } else {
                        val chatUser = XposedHelpers2.getObjectField<Any>(itemData, "field_username") as? String ?: return
                        if (WXMaskPlugin.containChatUser(chatUser)) {
                            hideUnReadTipView(itemView, param)
                            hideMsgViewItemText(itemView, param)
                        }
                    }
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
                        } catch (e: Throwable) {}
                    } else {
                        val ClazzNoMeasuredTextView = ClazzN.from("com.tencent.mm.ui.base.NoMeasuredTextView")
                        ChildDeepCheck().each(itemView) { child ->
                            try {
                                if (ClazzNoMeasuredTextView?.isAssignableFrom(child::class.java) == true
                                    || TextView::class.java.isAssignableFrom(child::class.java)
                                ) {
                                    XposedHelpers2.callMethod<String?>(child, "setText", "")
                                }
                            } catch (e: Throwable) {}
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
        if (methods.size > 0) {
            method = methods[0]
        }
        return method
    }

    private fun handleMainUIChattingListView2(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        val adapterClazzName = when (AppVersionUtil.getVersionCode()) {
            Constrant.WX_CODE_8_0_22 -> "com.tencent.mm.ui.g"
            in Constrant.WX_CODE_8_0_32..Constrant.WX_CODE_8_0_34 -> "com.tencent.mm.ui.y"
            in Constrant.WX_CODE_8_0_35..Constrant.WX_CODE_8_0_38 -> "com.tencent.mm.ui.z"
            in Constrant.WX_CODE_8_0_40..Constrant.WX_CODE_8_0_43 -> "com.tencent.mm.ui.b0"
            in Constrant.WX_CODE_8_0_43..Constrant.WX_CODE_8_0_44 -> "com.tencent.mm.ui.h3"
            in Constrant.WX_CODE_8_0_43..Constrant.WX_CODE_8_0_47,
            Constrant.WX_CODE_PLAY_8_0_48, Constrant.WX_CODE_8_0_50, Constrant.WX_CODE_8_0_51, Constrant.WX_CODE_8_0_53, Constrant.WX_CODE_8_0_56,-> "com.tencent.mm.ui.i3"
            in Constrant.WX_CODE_8_0_58..Constrant.WX_CODE_8_0_60 -> "com.tencent.mm.ui.k3"
            Constrant.WX_CODE_8_0_69 -> "o75.v0" 
            else -> null
        }
        
        var adapterClazz = if (adapterClazzName != null) ClazzN.from(adapterClazzName) else null

        if (adapterClazz != null) {
            var getItemMethod = findGetItemMethod(adapterClazz)
            if (getItemMethod != null) {
                hookListViewGetItem(getItemMethod)
            }
            
            // 【史诗级修复】！！！
            // 之前的代码在这里找到了 o75.v0 就直接 return 退出了！
            // 导致根本没去执行下面的 hookListViewAdapter，也就没去拦截界面。
            // 现在强制让它去拦截界面！
            hookListViewAdapter(adapterClazz)
            
            return
        }

        XposedHelpers2.findAndHookMethod(
            ListView::class.java,
            "setAdapter",
            ListAdapter::class.java,
            object : XC_MethodHook2() {
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
            }
        )
    }

    private fun hookListViewGetItem(getItemMethod: Method) {
        XposedHelpers2.hookMethod(
            getItemMethod,
            object : XC_MethodHook2() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val itemData: Any = param.result ?: return
                    val chatUser: String? = XposedHelpers2.getObjectField(itemData, "field_username")
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
            }
        )
    }
}
