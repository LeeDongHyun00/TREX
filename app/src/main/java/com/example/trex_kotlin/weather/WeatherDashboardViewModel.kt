package com.example.trex_kotlin.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

class WeatherDashboardViewModel(
    private val repository: WeatherRepository
) : ViewModel() {
    private val _state = MutableStateFlow(
        WeatherDashboardState(
            items = KoreanRegions.representatives.map {
                WeatherDashboardItem(region = it, isLoading = true)
            }
        )
    )
    val state: StateFlow<WeatherDashboardState> = _state.asStateFlow()

    private var generation = 0L

    init {
        refresh()
    }

    fun refresh(forceRefresh: Boolean = false) {
        val requestGeneration = ++generation
        _state.update { current ->
            current.copy(
                isRefreshing = true,
                items = current.items.map { item ->
                    item.copy(isLoading = true, errorMessage = null)
                }
            )
        }

        viewModelScope.launch {
            supervisorScope {
                KoreanRegions.representatives.map { region ->
                    async(Dispatchers.IO) {
                        val result = try {
                            repository.fetchCurrentWeather(
                                latitude = region.latitude,
                                longitude = region.longitude,
                                forceRefresh = forceRefresh
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            Result.failure(error)
                        }

                        if (requestGeneration == generation) {
                            applyResult(region, result)
                        }
                    }
                }.forEach { it.await() }
            }

            if (requestGeneration == generation) {
                _state.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun retry(regionId: String) {
        val region = KoreanRegions.representatives.firstOrNull { it.id == regionId } ?: return
        val requestGeneration = generation

        _state.update { current ->
            current.copy(items = current.items.map { item ->
                if (item.region.id == regionId) {
                    item.copy(isLoading = true, errorMessage = null)
                } else item
            })
        }

        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                repository.fetchCurrentWeather(
                    latitude = region.latitude,
                    longitude = region.longitude,
                    forceRefresh = true
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (requestGeneration == generation) applyResult(region, result)
        }
    }

    private fun applyResult(
        region: KoreanRegion.Representative,
        result: Result<WeatherInfo>
    ) {
        _state.update { current ->
            current.copy(items = current.items.map { item ->
                if (item.region.id != region.id) return@map item
                result.fold(
                    onSuccess = { weather ->
                        item.copy(
                            recommendation = WeatherExerciseRecommender.recommend(
                                weather = weather,
                                locationLabel = region.displayName
                            ),
                            isLoading = false,
                            errorMessage = null
                        )
                    },
                    onFailure = {
                        item.copy(
                            isLoading = false,
                            errorMessage = "날씨 정보를 불러오지 못했어요."
                        )
                    }
                )
            })
        }
    }
}
