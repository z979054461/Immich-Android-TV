package nl.giejay.android.tv.immich.settings

import android.app.Activity
import android.app.AlertDialog
import nl.giejay.android.tv.immich.MainActivity
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.navigation.fragment.findNavController
import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.home.HomeFragmentDirections
import nl.giejay.android.tv.immich.shared.prefs.ScreensaverPrefScreen
import nl.giejay.android.tv.immich.shared.prefs.ViewPrefScreen
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager


class SettingsFragment : RowsSupportFragment() {
    private val mRowsAdapter: ArrayObjectAdapter
    private var pinGraceDialog: AlertDialog? = null

    init {
        val selector = ListRowPresenter()
        selector.setNumRows(1)
        mRowsAdapter = ArrayObjectAdapter(selector)
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            val card = item as SettingsCard
            card.onClick()
        }
        adapter = mRowsAdapter
    }

    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        loadData()
    }

    private fun loadData() {
        if (isAdded) {
            mRowsAdapter.clear()
            mRowsAdapter.add(
                createCardRow(
                    listOf(
                        SettingsCard(
                            ImmichApplication.appContext!!.getString(R.string.profiles_switch),
                            null,
                            "server",
                            "ic_settings_settings",
                            "ic_settings_settings"
                        ) {
                            (activity as? MainActivity)?.lockAndShowProfiles()
                        },
                        SettingsCard(
                            getString(R.string.profiles_grace_title),
                            getString(R.string.profiles_grace_description),
                            "pin_grace",
                            "ic_settings_settings",
                            "ic_settings_settings"
                        ) {
                            PreferenceManager.activeProfileId?.let { id ->
                                pinGraceDialog?.dismiss()
                                pinGraceDialog = PinGraceSettingsDialog.show(requireContext(), id)
                            }
                        },
                        SettingsCard(
                            ImmichApplication.appContext!!.getString(R.string.view_settings),
                            null,
                            "view_settings",
                            "icon_view",
                            "icon_view"
                        ) {
                            findNavController().navigate(
                                HomeFragmentDirections.actionGlobalToSettingsDialog(ViewPrefScreen.key)
                            )
                        },
                        SettingsCard(
                            ImmichApplication.appContext!!.getString(R.string.screensaver),
                            null,
                            "screensaver",
                            "screensaver",
                            "ic_settings_settings"
                        ) {
                            findNavController().navigate(
                                HomeFragmentDirections.actionGlobalToSettingsDialog(ScreensaverPrefScreen.key)
                            )
                        }
                    )
                )
            )
            mainFragmentAdapter.fragmentHost?.notifyDataReady(
                mainFragmentAdapter
            )
        }
    }

    override fun onStop() {
        pinGraceDialog?.dismiss()
        pinGraceDialog = null
        super.onStop()
    }

    private fun createCardRow(cards: List<SettingsCard>): ListRow {
        val iconCardPresenter = SettingsIconPresenter(requireContext())
        val adapter = ArrayObjectAdapter(iconCardPresenter)
        adapter.addAll(0, cards.filter { it.visible })
        val headerItem = HeaderItem(ImmichApplication.appContext!!.getString(R.string.settings))
        return ListRow(headerItem, adapter)
    }
}
