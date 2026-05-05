package com.kail.location.utils

import android.app.Activity
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.appopen.AppOpenAd.AppOpenAdLoadCallback
import java.util.Date

open class AppOpenAdManager(private val adUnitId: String) {

    companion object {
        private const val LOG_TAG = "AppOpenAdManager"
        private const val MAX_CACHE_HOURS = 4L
    }

    private var appOpenAd: AppOpenAd? = null
    var isLoadingAd = false
        private set
    var isShowingAd = false
        private set
    private var loadTime: Long = 0

    fun loadAd(activity: Activity) {
        if (isLoadingAd || isAdAvailable()) return
        isLoadingAd = true
        val request = AdRequest.Builder().build()
        AppOpenAd.load(activity, adUnitId, request, object : AppOpenAdLoadCallback() {
            override fun onAdLoaded(ad: AppOpenAd) {
                Log.d(LOG_TAG, "App open ad loaded.")
                appOpenAd = ad
                isLoadingAd = false
                loadTime = Date().time
            }

            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                Log.d(LOG_TAG, "App open ad failed to load: ${loadAdError.message}")
                isLoadingAd = false
            }
        })
    }

    fun showAdIfAvailable(activity: Activity, onComplete: () -> Unit = {}) {
        if (isShowingAd) {
            Log.d(LOG_TAG, "The app open ad is already showing.")
            return
        }
        if (!isAdAvailable()) {
            Log.d(LOG_TAG, "The app open ad is not ready yet.")
            onComplete()
            loadAd(activity)
            return
        }

        isShowingAd = true
        appOpenAd?.setFullScreenContentCallback(object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.d(LOG_TAG, "Ad dismissed fullscreen content.")
                appOpenAd = null
                isShowingAd = false
                onComplete()
                loadAd(activity)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Log.d(LOG_TAG, adError.message)
                appOpenAd = null
                isShowingAd = false
                onComplete()
                loadAd(activity)
            }

            override fun onAdShowedFullScreenContent() {
                Log.d(LOG_TAG, "Ad showed fullscreen content.")
            }

            override fun onAdImpression() {
                Log.d(LOG_TAG, "The ad recorded an impression.")
            }

            override fun onAdClicked() {
                Log.d(LOG_TAG, "The ad was clicked.")
            }
        })
        appOpenAd?.show(activity)
    }

    private fun isAdAvailable(): Boolean {
        return appOpenAd != null && wasLoadTimeLessThanNHoursAgo(MAX_CACHE_HOURS)
    }

    private fun wasLoadTimeLessThanNHoursAgo(numHours: Long): Boolean {
        val dateDifference = Date().time - loadTime
        val numMilliSecondsPerHour: Long = 3600000
        return dateDifference < numMilliSecondsPerHour * numHours
    }
}
