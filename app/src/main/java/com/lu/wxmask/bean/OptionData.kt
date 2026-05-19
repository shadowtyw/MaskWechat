package com.lu.wxmask.bean

import org.json.JSONObject


class OptionData
/**
 * @param hideMainSearch 主页搜索隐藏
 * @param enableMapConversation 主页消息变脸
 * @param hideSingleSearch 单聊搜索隐藏
 * @param hideMainSearchStrong 主页搜索，暴力隐藏（已废弃）
 * @param viewWxDbPw 查看微信数据库密码
 * @param travelTime 时间穿越（单位/毫秒）
 * @param enableTravelTime 是否启用时间穿越
 * @param enableChattingKey 聊天输入框口令监听
 * @param hideCloseFriend 隐藏亲密好友（朋友圈）
 * @param closeFriendIds 亲密好友ID列表
 * @param hideContact 隐藏通讯录联系人
 * @param contactHideIds 通讯录隐藏ID列表
 * @param enableTripleTapPassword 启用三击密码
 * @param tripleTapPassword 三击密码
 */
private constructor(
    var hideMainSearch: Boolean,
    var enableMapConversation: Boolean,
    var hideSingleSearch: Boolean,
    var hideMainSearchStrong: Boolean,
    var viewWxDbPw: Boolean,
    var travelTime: Long,
    var enableTravelTime: Boolean,
    var enableChattingKey: Boolean,
    // 新增 8.0.70+ 功能字段
    var hideCloseFriend: Boolean,
    var closeFriendIds: Set<String>?,
    var hideContact: Boolean,
    var contactHideIds: Set<String>?,
    var enableTripleTapPassword: Boolean,
    var tripleTapPassword: String?
) {


    companion object {
        fun fromJson(jsonText: String): OptionData {
            val json = try {
                JSONObject(jsonText)
            } catch (e: Exception) {
                JSONObject()
            }
            return OptionData(
                hideMainSearch = json.optBoolean("hideMainSearch", true),
                enableMapConversation = json.optBoolean("enableMapConversation", false),
                hideSingleSearch = json.optBoolean("hideSingleSearch", true),
                hideMainSearchStrong = json.optBoolean("hideMainSearchStrong", false),
                viewWxDbPw = json.optBoolean("viewWxDbPw", false),
                travelTime = json.optLong("travelTime", 0L),
                enableTravelTime = json.optBoolean("enableTravelTime", false),
                enableChattingKey = json.optBoolean("enableChattingKey", true),
                // 新增 8.0.70+ 功能字段
                hideCloseFriend = json.optBoolean("hideCloseFriend", false),
                closeFriendIds = json.optJSONArray("closeFriendIds")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { it.isNotBlank() } }.toSet()
                },
                hideContact = json.optBoolean("hideContact", false),
                contactHideIds = json.optJSONArray("contactHideIds")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { it.isNotBlank() } }.toSet()
                },
                enableTripleTapPassword = json.optBoolean("enableTripleTapPassword", false),
                tripleTapPassword = json.optString("tripleTapPassword").takeIf { it.isNotBlank() }
            )
        }
        fun toJson(data: OptionData): String {
            return JSONObject().apply {
                put("hideMainSearch", data.hideMainSearch)
                put("enableMapConversation", data.enableMapConversation)
                put("hideSingleSearch", data.hideSingleSearch)
                put("hideMainSearchStrong", data.hideMainSearchStrong)
                put("viewWxDbPw", data.viewWxDbPw)
                put("travelTime", data.travelTime)
                put("enableTravelTime", data.enableTravelTime)
                put("enableChattingKey", data.enableChattingKey)
                // 新增 8.0.70+ 功能字段
                put("hideCloseFriend", data.hideCloseFriend)
                data.closeFriendIds?.let {
                    put("closeFriendIds", org.json.JSONArray(it))
                }
                put("hideContact", data.hideContact)
                data.contactHideIds?.let {
                    put("contactHideIds", org.json.JSONArray(it))
                }
                put("enableTripleTapPassword", data.enableTripleTapPassword)
                data.tripleTapPassword?.let {
                    put("tripleTapPassword", it)
                }
            }.toString()
        }

    }
}
