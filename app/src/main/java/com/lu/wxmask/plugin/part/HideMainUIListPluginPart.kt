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
        val versionCode = AppVersionUtil.getVersionCode()
        
        if (versionCode >= Constrant.WX_CODE_8_0_70) {
            // 8.0.70+ 使用全新的 pipeline
            LogUtil.d("Using 8.0.70+ pipeline for hide main UI list")
            handleWechat8070ConversationPipeline(context, lpparam)
            return
        }
        
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

                override fun afterHookedMethod(param: MethodHookParam) {
                    val adapter: ListAdapter = param.thisObject as ListAdapter
                    val position: Int = (param.args[0] as? Int?) ?: return
                    val itemData: Any = adapter.getItem(position) ?: return

                    LogUtil.d("after getView", adapter.javaClass, GsonUtil.toJson(itemData))
                    if (baseConversationClazz?.isAssignableFrom(itemData.javaClass) != true
                        && !itemData::class.java.name.startsWith("com.tencent.mm.storage")
                    ) {
                        //不是所需类型
                        //LogUtil.d(chatUser, GsonUtil.toJson(itemData))
                        LogUtil.w(
                            AppVersionUtil.getSmartVersionName(),
                            "类型检查错误，尝试继续",
                            itemData::class.java,
                            itemData::class.java.classes
                        )
                    }
                    val chatUser: String = XposedHelpers2.getObjectField(itemData, "field_username") ?: return
                    val itemView: View = param.args[1] as? View ?: return
                    if (WXMaskPlugin.containChatUser(chatUser)) {
                        hideUnReadTipView(itemView, param)
                        hideMsgViewItemText(itemView, param)
//                        hideLastMsgTime(itemView, param)
                    }
                }

                //消息条目，时间，暂不隐藏？改成去年？
                private fun hideLastMsgTime(itemView: View, params: MethodHookParam) {
                    val viewId = ResUtil.getViewId("l0s")
                    itemView.findViewById<View>(viewId)?.visibility = View.INVISIBLE

                }

                //隐藏未读消息红点
                private fun hideUnReadTipView(itemView: View, param: MethodHookParam) {
                    //带文字的未读红点
                    // Res TextView under com.tencent.mm.ui.conversation.ConversationFolderItemView
                    val tipTvIdTextID = when (AppVersionUtil.getVersionCode()) {
                        in 0..Constrant.WX_CODE_8_0_22 -> "tipcnt_tv"
                        Constrant.WX_CODE_PLAY_8_0_42 -> "oqu"
                        in Constrant.WX_CODE_8_0_22..Constrant.WX_CODE_8_0_41 -> "kmv"
                        else -> "kmv"
                    }
                    val tipTvId = ResUtil.getViewId(tipTvIdTextID)
                    itemView.findViewById<View>(tipTvId)?.visibility = View.INVISIBLE

                    //头像上的小红点
                    val small_red = when (AppVersionUtil.getVersionCode()) {
                        in 0..Constrant.WX_CODE_8_0_40 -> "a2f"
                        Constrant.WX_CODE_PLAY_8_0_42 -> "a_w"
                        Constrant.WX_CODE_8_0_41 -> "o_u"
                        else -> "o_u"
                    }
                    val viewId = ResUtil.getViewId(small_red)
                    itemView.findViewById<View>(viewId)?.visibility = View.INVISIBLE
                }

                //隐藏最后一条消息等
                private fun hideMsgViewItemText(itemView: View, param: MethodHookParam) {
                    // Res com.tencent.mm.ui.base.NoMeasuredTextView (tag last_msg_tv) under com.tencent.mm.ui.conversation.ConversationFolderItemView
                    val msgTvIdName = when (AppVersionUtil.getVersionCode()) {
                        in 0..Constrant.WX_CODE_8_0_22 -> "last_msg_tv"
                        in Constrant.WX_CODE_8_0_22..Constrant.WX_CODE_8_0_40 -> "fhs"
                        Constrant.WX_CODE_PLAY_8_0_42 -> "i2_"
                        Constrant.WX_CODE_8_0_41 -> "ht5"
                        else -> "ht5"
                    }
                    val lastMsgViewId = ResUtil.getViewId(msgTvIdName)
                    LogUtil.d("mask last msg textView", lastMsgViewId)
                    if (lastMsgViewId != 0 && lastMsgViewId != View.NO_ID) {
                        try {
                            val msgTv: View? = itemView.findViewById(lastMsgViewId)
                            XposedHelpers2.callMethod<Any?>(msgTv, "setText", "")
                        } catch (e: Throwable) {
                            LogUtil.w("error", e)
                        }
                    } else {
                        //
                        LogUtil.w("主页last消息id版本不适配，开启暴力隐藏", AppVersionUtil.getSmartVersionName())
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
        //listAdapter getItem方法被重命名了
        //8.0.32-8.0.34 com.tencent.mm.ui.y
        //8.0.35-8.0.37　　com.tencent.mm.ui.z
        //搞实际Adapter的父类，是个抽象类
        val adapterClazzName = when (AppVersionUtil.getVersionCode()) {
            Constrant.WX_CODE_8_0_22 -> "com.tencent.mm.ui.g"
            in Constrant.WX_CODE_8_0_32..Constrant.WX_CODE_8_0_34 -> "com.tencent.mm.ui.y"
            in Constrant.WX_CODE_8_0_35..Constrant.WX_CODE_8_0_38 -> "com.tencent.mm.ui.z"
            in Constrant.WX_CODE_8_0_40..Constrant.WX_CODE_8_0_43 -> "com.tencent.mm.ui.b0" // WX_CODE_PLAY_8_0_42 matches
            in Constrant.WX_CODE_8_0_43..Constrant.WX_CODE_8_0_44 -> "com.tencent.mm.ui.h3"
            in Constrant.WX_CODE_8_0_43..Constrant.WX_CODE_8_0_47,
            Constrant.WX_CODE_PLAY_8_0_48, Constrant.WX_CODE_8_0_50, Constrant.WX_CODE_8_0_51, Constrant.WX_CODE_8_0_53, Constrant.WX_CODE_8_0_56,-> "com.tencent.mm.ui.i3"
            in Constrant.WX_CODE_8_0_58..Constrant.WX_CODE_8_0_60 -> "com.tencent.mm.ui.k3"
            Constrant.WX_CODE_8_0_72 -> "com.tencent.mm.ui.conversation.tb"
            else -> {
                LogUtil.w("WeChat version not explicitly mapped, will use fallback.")
                null
            }
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
                        // Try superclass first, then adapter class itself, then BaseAdapter.getItem
                        var getItemMethod = findGetItemMethod(adapter::class.java.superclass)
                        if (getItemMethod == null) {
                            getItemMethod = findGetItemMethod(adapter::class.java)
                        }
                        if (getItemMethod == null) {
                            getItemMethod = XposedHelpers2.findMethodExactIfExists(adapter::class.java.superclass, "getItem", Integer.TYPE)
                        }
                        if (getItemMethod == null) {
                            getItemMethod = XposedHelpers2.findMethodExactIfExists(adapter::class.java, "getItem", Integer.TYPE)
                        }
                        if (getItemMethod == null) {
                            // Last resort: hook android.widget.BaseAdapter.getItem directly
                            getItemMethod = XposedHelpers2.findMethodExactIfExists(
                                android.widget.BaseAdapter::class.java, "getItem", Integer.TYPE
                            )
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
                        LogUtil.w("chat user is null")
                        return
                    }
                    if (WXMaskPlugin.containChatUser(chatUser)) {
//                        LogUtil.i("item-data", GsonUtil.toJson(itemData))
                        val option = ConfigUtil.getOptionData()
                        if (option.enableMapConversation) {
                            var maskBean = WXMaskPlugin.getMaskBeamById(chatUser)?.let {
                                XposedHelpers2.setObjectField(itemData, "field_username", it.mapId)
                            }

                        }
                        //field_editingMsg 上次输入框输入的内容，没有发送出去
                        XposedHelpers2.setObjectField(itemData, "field_content", "")
                        XposedHelpers2.setObjectField(itemData, "field_digest", "")
                        XposedHelpers2.setObjectField(itemData, "field_unReadCount", 0)
                        XposedHelpers2.setObjectField(itemData, "field_UnReadInvite", 0)
                        XposedHelpers2.setObjectField(itemData, "field_unReadMuteCount", 0)
                        //标注成文本消息，不显示表情等
                        XposedHelpers2.setObjectField(itemData, "field_msgType", "1")

                        if (option.enableTravelTime && option.travelTime != 0L) {
                            val cTime = XposedHelpers2.getObjectField<Any>(itemData, "field_conversationTime")
                            if (cTime is Long) {
                                XposedHelpers2.setObjectField(itemData, "field_conversationTime", cTime - option.travelTime)
                            }
                        }
                        // 恢复被置底的好友
                        // try {
                        //     val cTime = XposedHelpers2.getObjectField<Any>(itemData, "field_conversationTime")
                        //     val fieldFlag = XposedHelpers2.getObjectField<Any>(itemData, "field_flag")
                        //     if (cTime != null && fieldFlag != cTime) {
                        //         XposedHelpers2.setObjectField(itemData, "field_flag", cTime)
                        //     }
                        // } catch (e: Exception) {
                        //     e.printStackTrace()
                        // }

                    }


                }

            }
        )
    }

    // ==================== 8.0.70+ Pipeline ====================

    /**
     * 8.0.70+ 会话列表处理 pipeline
     * 不再依赖 ListView.getItem，而是直接 hook 数据源
     */
    private fun handleWechat8070ConversationPipeline(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        LogUtil.d("handleWechat8070ConversationPipeline: starting")
        
        // 1. Hook 数据源
        hookWechat8070ConversationDataSource(context, lpparam)
        
        // 2. Hook 项构建器
        hookWechat8070ConversationItemBuilder(context, lpparam)
        
        // 3. Hook 最后一条消息 TextView
        hookWechat8070LastMsgTextView(context, lpparam)
        
        // 4. Hook Adapter
        hookWechat8070ConversationAdapter(context, lpparam)
        
        // 5. Hook 数据库写入
        hookHiddenMessageDatabaseWrites(context, lpparam)
        
        // 6. Hook 通知
        hookHiddenMessageNotifications(context, lpparam)
    }

    /**
     * Hook 数据源更新
     */
    private fun hookWechat8070ConversationDataSource(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            // 尝试找 DataSource 类
            val dataSourceClazz = findConversationDataSourceClazz(context) ?: return@runCatching
            
            LogUtil.d("hookWechat8070ConversationDataSource: ${dataSourceClazz.name}")
            
            // Hook 数据更新方法
            XposedHelpers2.findAndHookMethod(
                dataSourceClazz,
                "updateData",
                List::class.java,
                object : XC_MethodHook2() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val items = param.args[0] as? List<*> ?: return
                        LogUtil.d("wx8070-data-source-update: ${items.size} items")
                        
                        val filtered = filterConversationItems8070(items, context)
                        if (filtered.size != items.size) {
                            param.args[0] = filtered
                            LogUtil.d("wx8070-data-source-update: filtered to ${filtered.size}")
                        }
                    }
                }
            )
        }.onFailure {
            LogUtil.w("hookWechat8070ConversationDataSource failed", it)
        }
    }

    /**
     * Hook 项构建器
     */
    private fun hookWechat8070ConversationItemBuilder(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val itemBuilderClazz = findConversationItemBuilderClazz(context) ?: return@runCatching
            
            LogUtil.d("hookWechat8070ConversationItemBuilder: ${itemBuilderClazz.name}")
            
            // Hook buildItem 或类似方法
            XposedHelpers2.findAndHookMethod(
                itemBuilderClazz,
                "buildItem",
                Any::class.java,
                object : XC_MethodHook2() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val itemData = param.args[0] ?: return
                        val username = getConversationItemUsername(itemData, context) ?: return
                        
                        if (isMaskedConversationUser(username)) {
                            // 清理会话项数据
                            scrubConversationItemData(itemData)
                            LogUtil.d("wx8070-item-builder: scrubbed $username")
                        }
                    }
                }
            )
        }.onFailure {
            LogUtil.w("hookWechat8070ConversationItemBuilder failed", it)
        }
    }

    /**
     * Hook 最后一条消息 TextView
     */
    private fun hookWechat8070LastMsgTextView(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers2.findAndHookMethod(
                TextView::class.java,
                "setText",
                CharSequence::class.java,
                TextView.BufferType::class.java,
                object : XC_MethodHook2() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val textView = param.thisObject as? TextView ?: return
                        if (!isConversationLastMsgTextView(textView, context)) return
                        
                        // 检查是否属于隐藏用户
                        val itemData = getConversationItemFromView(textView) ?: return
                        val username = getConversationItemUsername(itemData, context) ?: return
                        
                        if (isMaskedConversationUser(username)) {
                            param.args[0] = ""
                            LogUtil.d("wx8070-last-msg-set-text: cleared for $username")
                        }
                    }
                }
            )
        }.onFailure {
            LogUtil.w("hookWechat8070LastMsgTextView failed", it)
        }
    }

    /**
     * Hook Conversation Adapter
     */
    private fun hookWechat8070ConversationAdapter(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val adapterClazz = findConversationAdapterClazz(context) ?: return@runCatching
            
            LogUtil.d("hookWechat8070ConversationAdapter: ${adapterClazz.name}")
            
            // Hook onBindViewHolder (RecyclerView.Adapter)
            val viewHolderClass = runCatching {
                context.classLoader.loadClass("androidx.recyclerview.widget.RecyclerView\$ViewHolder")
            }.getOrNull()
            if (viewHolderClass == null) {
                LogUtil.w("hookWechat8070ConversationAdapter: ViewHolder class not found, skipping")
                return@runCatching
            }
            XposedHelpers2.findAndHookMethod(
                adapterClazz,
                "onBindViewHolder",
                viewHolderClass,
                Int::class.java,
                object : XC_MethodHook2() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val holder = param.args[0] ?: return
                        val position = param.args[1] as Int
                        
                        val itemData = getConversationItemFromHolder(holder, context) ?: return
                        val username = getConversationItemUsername(itemData, context) ?: return
                        
                        if (isMaskedConversationUser(username)) {
                            // 隐藏 itemView
                            hideConversationItemView(holder)
                            LogUtil.d("wx8070-adapter-bind: hiding $username at $position")
                        }
                    }
                }
            )
        }.onFailure {
            LogUtil.w("hookWechat8070ConversationAdapter failed", it)
        }
    }

    /**
     * Hook 隐藏消息的数据库写入
     */
    private fun hookHiddenMessageDatabaseWrites(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val storageClazz = XposedHelpers2.findClass(
                "com.tencent.mm.storage.BaseStorage",
                context.classLoader
            ) ?: return@runCatching
            
            XposedHelpers2.findAndHookMethod(
                storageClazz,
                "insert",
                String::class.java,
                Any::class.java,
                object : XC_MethodHook2() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val tableName = param.args[0] as? String ?: return
                        val data = param.args[1] ?: return
                        
                        if (isConversationTable(tableName)) {
                            val username = getConversationItemUsername(data, context) ?: return
                            if (isMaskedConversationUser(username)) {
                                // 标记为隐藏，不阻断写入
                                markHiddenUnread(context, username)
                            }
                        }
                    }
                }
            )
        }.onFailure {
            LogUtil.w("hookHiddenMessageDatabaseWrites failed", it)
        }
    }

    /**
     * Hook 隐藏消息的通知
     */
    private fun hookHiddenMessageNotifications(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            XposedHelpers2.findAndHookMethod(
                android.app.NotificationManager::class.java,
                "notify",
                String::class.java,
                Int::class.java,
                android.app.Notification::class.java,
                object : XC_MethodHook2() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val tag = param.args[0] as? String ?: return
                        val notification = param.args[2] as? android.app.Notification ?: return
                        
                        // 检查通知内容是否属于隐藏用户
                        val text = notification.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
                        val hiddenUser = findHiddenUserInText(text, context)
                        
                        if (hiddenUser != null) {
                            // 隐藏通知
                            param.result = null
                            LogUtil.d("wx8070-notification: hidden for $hiddenUser")
                            vibrateForHiddenMessage(context)
                        }
                    }
                }
            )
        }.onFailure {
            LogUtil.w("hookHiddenMessageNotifications failed", it)
        }
    }

    // ==================== 8.0.70+ 辅助方法 ====================

    private fun findConversationDataSourceClazz(context: Context): Class<*>? {
        return runCatching {
            context.classLoader.loadClass("com.tencent.mm.ui.conversation.adapter.ConversationDataSource")
        }.getOrNull() ?: runCatching {
            context.classLoader.loadClass("com.tencent.mm.ui.conversation.MvvmConvList")
        }.getOrNull()
    }

    private fun findConversationItemBuilderClazz(context: Context): Class<*>? {
        return runCatching {
            context.classLoader.loadClass("com.tencent.mm.ui.conversation.adapter.ConversationItemBuilder")
        }.getOrNull() ?: runCatching {
            context.classLoader.loadClass("com.tencent.mm.ui.conversation.ItemBuilder")
        }.getOrNull()
    }

    private fun findConversationAdapterClazz(context: Context): Class<*>? {
        return runCatching {
            context.classLoader.loadClass("com.tencent.mm.ui.conversation.ConversationAdapter")
        }.getOrNull() ?: runCatching {
            context.classLoader.loadClass("com.tencent.mm.ui.conversation.adapter.ConversationAdapter")
        }.getOrNull() ?: runCatching {
            // 8.0.72 的适配器类
            context.classLoader.loadClass("com.tencent.mm.ui.conversation.tb")
        }.getOrNull()
    }

    private fun filterConversationItems8070(items: List<*>, context: Context): List<Any> {
        val option = ConfigUtil.getOptionData()
        return items.filterNotNull().filter { item ->
            val username = getConversationItemUsername(item, context) ?: return@filter true
            !isMaskedConversationUser(username)
        }
    }

    private fun isConversationLastMsgTextView(view: TextView, context: Context): Boolean {
        val id = view.id
        if (id == View.NO_ID) return false
        
        val idName = context.resources.getResourceEntryName(id)
        return idName.contains("last_msg") || idName.contains("msg") || idName == "ht5"
    }

    private fun getConversationItemFromView(view: View): Any? {
        return runCatching {
            val parent = view.parent ?: return@runCatching null
            XposedHelpers2.getObjectField<Any?>(parent, "itemData")
        }.getOrNull() ?: runCatching {
            val parent = view.parent as? ViewGroup ?: return@runCatching null
            XposedHelpers2.getObjectField<Any?>(parent, "item")
        }.getOrNull()
    }

    private fun getConversationItemFromHolder(holder: Any, context: Context): Any? {
        return runCatching {
            XposedHelpers2.getObjectField<Any?>(holder, "itemData")
        }.getOrNull() ?: runCatching {
            XposedHelpers2.getObjectField<Any?>(holder, "item")
        }.getOrNull() ?: runCatching {
            XposedHelpers2.getObjectField<Any?>(holder, "data")
        }.getOrNull()
    }

    private fun hideConversationItemView(holder: Any) {
        runCatching {
            val itemView = XposedHelpers2.getObjectField<View>(holder, "itemView") ?: return@runCatching
            itemView.visibility = View.GONE
            val lp = itemView.layoutParams
            lp?.let {
                if (it is ViewGroup.LayoutParams) {
                    it.height = 1
                    itemView.layoutParams = it
                }
            }
        }.onFailure {
            // ignore
        }
    }

    private fun isConversationTable(tableName: String): Boolean {
        return tableName.contains("rconversation", ignoreCase = true) ||
               tableName == "conversation"
    }

    private fun markHiddenUnread(context: Context, username: String) {
        // 标记隐藏用户的未读状态
        runCatching {
            WXMaskPlugin.markHiddenUnread(username)
        }
    }

    private fun findHiddenUserInText(text: String, context: Context): String? {
        for (wxid in WXMaskPlugin.getMaskIdList()) {
            if (text.contains(wxid)) return wxid
        }
        return null
    }

    private fun vibrateForHiddenMessage(context: Context) {
        runCatching {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            vibrator?.vibrate(50)
        }
    }

    private fun requestRefreshVisibleConversationList(context: Context) {
        runCatching {
            // 刷新会话列表
            LogUtil.d("requestRefreshVisibleConversationList")
        }
    }

    /**
     * 判断用户是否为隐藏用户
     */
    private fun isMaskedConversationUser(username: String): Boolean {
        return WXMaskPlugin.containChatUser(username)
    }

    /**
     * 清理会话项数据
     */
    private fun scrubConversationItemData(itemData: Any) {
        runCatching {
            XposedHelpers2.setObjectField(itemData, "field_content", "")
            XposedHelpers2.setObjectField(itemData, "field_digest", "")
            XposedHelpers2.setObjectField(itemData, "field_unReadCount", 0)
            XposedHelpers2.setObjectField(itemData, "field_UnReadInvite", 0)
            XposedHelpers2.setObjectField(itemData, "field_unReadMuteCount", 0)
            XposedHelpers2.setObjectField(itemData, "field_msgType", "1")
        }.onFailure {
            LogUtil.w("scrubConversationItemData failed", it)
        }
    }

    /**
     * 从会话项中获取用户名
     */
    private fun getConversationItemUsername(item: Any, context: Context): String? {
        return runCatching {
            XposedHelpers2.getObjectField<String?>(item, "field_username")
        }.getOrNull() ?: runCatching {
            XposedHelpers2.getObjectField<String?>(item, "username")
        }.getOrNull()
    }

}
