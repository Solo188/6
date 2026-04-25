package com.instacapture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * LogoutAutomator — автоматический выход из Instagram через Accessibility API.
 * Проблема: без root невозможно полностью очистить данные приложения.
 * Решение: автоматизировать UI-шаги выхода из аккаунта.
 *
 * ВНИМАНИЕ: Этот метод НЕ гарантирует полную очистку (кэш, сохранённые логины
 * могут остаться). Для гарантии рекомендуется вручную очистить данные Instagram
 * в системных настройках.
 */
object LogoutAutomator {

    private const val TAG = "InstaCapture:Logout"

    /**
     * Выполняет полный цикл выхода из Instagram.
     * @return true если все шаги выполнены успешно, false при ошибке
     */
    fun performLogout(service: AccessibilityService): Boolean {
        Log.i(TAG, "Начало автоматического logout...")

        val rootNode = service.rootInActiveWindow
        if (rootNode == null) {
            Log.e(TAG, "rootInActiveWindow == null — Instagram не на экране")
            return false
        }

        return try {
            // Шаг 1: Открыть профиль
            val profileTab = findNodeByContentDescription(rootNode, "Profile", "Профиль")
                ?: findNodeByText(rootNode, "Profile", "Профиль")
                ?: findNodeByResourceId(rootNode, "tab_bar_profile", "profile_tab")

            if (profileTab != null) {
                clickNode(service, profileTab, "Профиль")
                safeSleep(600)
            } else {
                Log.w(TAG, "Вкладка профиля не найдена — возможно уже в профиле")
            }

            // Шаг 2: Открыть меню
            val updatedRoot = service.rootInActiveWindow ?: rootNode
            val menuButton = findNodeByContentDescription(updatedRoot, "Menu", "Меню", "Настройки")
                ?: findNodeByResourceId(updatedRoot, "menu", "hamburger", "options")
                ?: findNodeByText(updatedRoot, "≡", "Menu", "Меню")

            if (menuButton != null) {
                clickNode(service, menuButton, "Меню")
                safeSleep(800)
            }

            // Шаг 3: Найти Settings
            val menuRoot = service.rootInActiveWindow ?: updatedRoot
            val settings = findNodeByText(menuRoot, "Settings", "Настройки", "Settings and privacy", "Настройки и конфиденциальность")
                ?: findNodeByContentDescription(menuRoot, "Settings", "Настройки")

            if (settings != null) {
                clickNode(service, settings, "Настройки")
                safeSleep(800)
            } else {
                Log.e(TAG, "Пункт Настройки не найден")
                return false
            }

            // Шаг 4: Прокрутить и найти Log Out
            val settingsRoot = service.rootInActiveWindow ?: menuRoot
            var logoutFound = false
            var scrollAttempts = 0
            val maxScrolls = 8

            while (!logoutFound && scrollAttempts < maxScrolls) {
                val currentRoot = service.rootInActiveWindow ?: settingsRoot
                val logout = findNodeByText(currentRoot, "Log Out", "Выйти", "Logout", "Log out")

                if (logout != null) {
                    logoutFound = true
                    clickNode(service, logout, "Выйти")
                    safeSleep(600)
                    break
                }

                val scrollable = findScrollableNode(currentRoot)
                if (scrollable != null) {
                    scrollDown(service, scrollable)
                    safeSleep(600)
                    scrollAttempts++
                } else {
                    Log.w(TAG, "Нет прокручиваемого контейнера")
                    break
                }
            }

            if (!logoutFound) {
                Log.e(TAG, "Кнопка 'Выйти' не найдена после $maxScrolls прокруток")
                return false
            }

            // Шаг 5: Подтвердить выход
            val confirmRoot = service.rootInActiveWindow
            val confirm = findNodeByText(confirmRoot, "Log Out", "Выйти", "Log out", "OK", "Yes")
            if (confirm != null) {
                clickNode(service, confirm, "Подтверждение выхода")
                safeSleep(500)
            }

            Log.i(TAG, "Logout выполнен успешно")
            Toast.makeText(service, R.string.toast_logout_success, Toast.LENGTH_LONG).show()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при logout: ${e.message}", e)
            false
        } finally {
            rootNode.recycle()
        }
    }

    // ==================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ====================

    private fun findNodeByContentDescription(root: AccessibilityNodeInfo?, vararg labels: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val nodes = root.findAccessibilityNodeInfosByText(labels[0])
        for (node in nodes) {
            val desc = node.contentDescription?.toString() ?: ""
            for (label in labels) {
                if (desc.contains(label, ignoreCase = true)) {
                    return node
                }
            }
            node.recycle()
        }
        return null
    }

    private fun findNodeByText(root: AccessibilityNodeInfo?, vararg texts: String): AccessibilityNodeInfo? {
        if (root == null) return null
        for (text in texts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            val match = nodes.firstOrNull()
            if (match != null) {
                // Recycle остальные
                nodes.filter { it != match }.forEach { it.recycle() }
                return match
            }
            nodes.forEach { it.recycle() }
        }
        return null
    }

    private fun findNodeByResourceId(root: AccessibilityNodeInfo?, vararg ids: String): AccessibilityNodeInfo? {
        if (root == null) return null
        for (id in ids) {
            val nodes = root.findAccessibilityNodeInfosByViewId("com.instagram.android:id/$id")
            val match = nodes.firstOrNull()
            if (match != null) {
                nodes.filter { it != match }.forEach { it.recycle() }
                return match
            }
            nodes.forEach { it.recycle() }
        }
        return null
    }

    private fun findScrollableNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (root.isScrollable) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            val found = findScrollableNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun clickNode(service: AccessibilityService, node: AccessibilityNodeInfo, label: String) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val x = rect.centerX().toFloat()
        val y = rect.centerY().toFloat()

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        service.dispatchGesture(gesture, null, null)
        Log.i(TAG, "Клик по '$label' ($x, $y)")
    }

    private fun scrollDown(service: AccessibilityService, node: AccessibilityNodeInfo) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val x = rect.centerX().toFloat()
        val fromY = rect.bottom * 0.7f
        val toY = rect.top * 0.3f

        val path = Path().apply {
            moveTo(x, fromY)
            lineTo(x, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()

        service.dispatchGesture(gesture, null, null)
        Log.d(TAG, "Прокрутка вниз")
    }

    private fun safeSleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
