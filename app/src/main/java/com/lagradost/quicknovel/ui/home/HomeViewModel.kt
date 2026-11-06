package com.lagradost.quicknovel.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.Apis.Companion.getApiProviderLangSettings

class HomeViewModel : ViewModel() {
    private val _homeApis = MutableLiveData<List<MainAPI>>()
    val homeApis: LiveData<List<MainAPI>> = _homeApis

    init {
        refreshApis()
    }

    fun refreshApis() {
        val langs = getApiProviderLangSettings()
        _homeApis.value = Apis.apis.filter { api ->
            api.hasMainPage && langs.contains(api.lang)
        }
    }
}
