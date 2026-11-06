package com.lagradost.quicknovel.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.quicknovel.extensions.ExtensionProviderRegistry
import kotlinx.coroutines.launch
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.util.Apis
import com.lagradost.quicknovel.util.Apis.Companion.getApiProviderLangSettings

class HomeViewModel : ViewModel() {
    private val _homeApis = MutableLiveData<List<MainAPI>>()
    val homeApis: LiveData<List<MainAPI>> = _homeApis

    init {
        refreshApis()
        // Settings can install/uninstall extensions while this ViewModel remains alive.
        // Rebuild the visible provider list immediately instead of waiting for a full
        // Fragment recreation or a manual app restart.
        viewModelScope.launch {
            ExtensionProviderRegistry.revision.collect {
                refreshApis()
            }
        }
    }

    fun refreshApis() {
        val langs = getApiProviderLangSettings()
        _homeApis.value = Apis.apis.filter { api ->
            api.hasMainPage && langs.contains(api.lang)
        }
    }
}
