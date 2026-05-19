package com.kail.location.views.independentsimulation

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.kail.location.views.base.BaseActivity
import com.kail.location.views.theme.locationTheme
import com.kail.location.viewmodels.IndependentSimulationViewModel

class IndependentSimulationActivity : BaseActivity() {
    private val viewModel: IndependentSimulationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            locationTheme {
                IndependentSimulationScreen(
                    viewModel = viewModel,
                    onBackClick = { finish() }
                )
            }
        }
    }
}
