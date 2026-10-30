package com.example.trex_kotlin.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun WeatherDashboard(
    repository: WeatherRepository,
    modifier: Modifier = Modifier,
    dashboardViewModel: WeatherDashboardViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                WeatherDashboardViewModel(repository) as T
        }
    )
) {
    val state by dashboardViewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "dashboard_controls") {
            Button(
                onClick = { dashboardViewModel.refresh(forceRefresh = true) },
                enabled = !state.isRefreshing
            ) {
                Text(if (state.isRefreshing) "새로고침 중" else "전체 새로고침")
            }
        }

        items(state.items, key = { it.region.id }) { item ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(item.region.displayName)
                        when {
                            item.isLoading && item.recommendation == null ->
                                CircularProgressIndicator()

                            item.recommendation != null -> {
                                Text(item.recommendation.weatherSummary)
                                Text(item.recommendation.precipitationSummary)
                                Text(item.recommendation.updateAgeText)
                            }
                        }

                        item.errorMessage?.let { message ->
                            Text(message)
                            TextButton(
                                onClick = {
                                    dashboardViewModel.retry(item.region.id)
                                }
                            ) {
                                Text("다시 시도")
                            }
                        }
                    }
                }

                if (item.region is KoreanRegion.Representative) {
                    val details = KoreanRegions.childrenOf(item.region.id)
                    if (details.isNotEmpty()) {
                        Text("세부 지역")
                        details.forEach { detail ->
                            Text("${detail.displayName} · ${detail.latitude}, ${detail.longitude}")
                        }
                    }
                }
            }
        }
    }
}
