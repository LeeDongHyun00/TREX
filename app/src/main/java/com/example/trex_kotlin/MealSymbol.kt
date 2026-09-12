package com.example.trex_kotlin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** 음식 예시 대신 끼니의 시간대를 상징한다. 인접한 끼니 이름이 접근성 이름을 제공한다. */
@Composable
internal fun MealSymbol(mealId: String, modifier: Modifier = Modifier) {
    val c = Trex.c
    Box(modifier.clip(CircleShape).background(c.primaryWash), contentAlignment = Alignment.Center) {
        Icon(mealIcon(mealId), contentDescription = null, tint = c.primaryText, modifier = Modifier.size(28.dp))
    }
}
