package com.ivi.car.navigation.ui

import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.ivi.car.navigation.R
import com.ivi.car.navigation.model.NavigationResultCode
import com.ivi.car.navigation.model.NavigationSuggestion
import com.ivi.car.navigation.model.NearbyCategory
import com.ivi.car.navigation.viewmodel.NaviViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class NavigationSearchDialogFragment : DialogFragment() {
    private val naviViewModel: NaviViewModel by activityViewModels()

    private lateinit var navigationState: TextView
    private lateinit var destinationInput: EditText
    private lateinit var searchProgress: ProgressBar
    private lateinit var searchMessage: TextView
    private lateinit var suggestionList: LinearLayout
    private lateinit var suggestionScroll: View
    private lateinit var placeDetail: View
    private lateinit var placeDetailName: TextView
    private lateinit var placeDetailPhoto: ImageView
    private lateinit var placeDetailMeta: TextView
    private lateinit var placeDetailAddress: TextView
    private lateinit var clearHome: Button
    private lateinit var clearWork: Button
    private lateinit var navigateHome: Button
    private lateinit var navigateWork: Button
    private lateinit var categoryButtons: Map<NearbyCategory, TextView>

    private var selectedSuggestion: NavigationSuggestion? = null
    private var initialSearchSubmitted = false
    private var destinationSearchRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_navigation_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        navigationState = view.findViewById(R.id.navigationState)
        destinationInput = view.findViewById(R.id.destinationInput)
        searchProgress = view.findViewById(R.id.searchProgress)
        searchMessage = view.findViewById(R.id.searchMessage)
        suggestionList = view.findViewById(R.id.suggestionList)
        suggestionScroll = view.findViewById(R.id.suggestionScroll)
        placeDetail = view.findViewById(R.id.placeDetail)
        placeDetailName = view.findViewById(R.id.placeDetailName)
        placeDetailPhoto = view.findViewById(R.id.placeDetailPhoto)
        placeDetailMeta = view.findViewById(R.id.placeDetailMeta)
        placeDetailAddress = view.findViewById(R.id.placeDetailAddress)
        clearHome = view.findViewById(R.id.clearHome)
        clearWork = view.findViewById(R.id.clearWork)
        navigateHome = view.findViewById(R.id.navigateHome)
        navigateWork = view.findViewById(R.id.navigateWork)
        categoryButtons = mapOf(
            NearbyCategory.RESTAURANT to view.findViewById(R.id.categoryRestaurant),
            NearbyCategory.HOTEL to view.findViewById(R.id.categoryHotel),
            NearbyCategory.HOSPITAL to view.findViewById(R.id.categoryHospital),
            NearbyCategory.GAS_STATION to view.findViewById(R.id.categoryGasStation),
            NearbyCategory.CONVENIENCE_STORE to view.findViewById(R.id.categoryConvenience)
        )

        view.findViewById<View>(R.id.closeDialog).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.setRoute).setOnClickListener { submitDestination() }
        destinationInput.setOnEditorActionListener { _, _, _ ->
            submitDestination()
            true
        }
        destinationInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                value: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                value: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) = Unit

            override fun afterTextChanged(value: Editable?) {
                destinationSearchRunnable?.let(destinationInput::removeCallbacks)
                val query = value?.toString()?.trim().orEmpty()
                if (query.length < MIN_AUTOCOMPLETE_LENGTH) return
                destinationSearchRunnable = Runnable {
                    closePlaceDetail()
                    naviViewModel.searchDestinations(query)
                }.also {
                    destinationInput.postDelayed(it, AUTOCOMPLETE_DELAY_MILLIS)
                }
            }
        })
        view.findViewById<Button>(R.id.useMapCenter).setOnClickListener {
            requestMapCenter(TARGET_ORIGIN)
        }
        view.findViewById<Button>(R.id.setMapCenterHome).setOnClickListener {
            requestMapCenter(TARGET_HOME)
        }
        view.findViewById<Button>(R.id.setMapCenterWork).setOnClickListener {
            requestMapCenter(TARGET_WORK)
        }
        categoryButtons.forEach { (category, button) ->
            button.setOnClickListener { searchNearby(category) }
        }
        navigateHome.setOnClickListener {
            handleNavigationResult(naviViewModel.startNavigatingHome(), isHome = true)
        }
        navigateWork.setOnClickListener {
            handleNavigationResult(naviViewModel.startNavigatingWork(), isHome = false)
        }
        clearHome.setOnClickListener {
            naviViewModel.clearHome()
            Toast.makeText(requireContext(), "Home removed", Toast.LENGTH_SHORT).show()
        }
        clearWork.setOnClickListener {
            naviViewModel.clearWork()
            Toast.makeText(requireContext(), "Work removed", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.closePlaceDetail).setOnClickListener {
            closePlaceDetail()
        }
        view.findViewById<Button>(R.id.placeDirections).setOnClickListener {
            selectedSuggestion?.let(::navigateToSuggestion)
        }

        observeState()

        val initialCategory = arguments
            ?.getInt(ARG_CATEGORY_CODE, NO_INITIAL_CATEGORY)
            ?.takeUnless { it == NO_INITIAL_CATEGORY }
            ?.let(NearbyCategory::fromCode)
        if (initialCategory != null && !initialSearchSubmitted) {
            initialSearchSubmitted = true
            view.post { searchNearby(initialCategory) }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.setCanceledOnTouchOutside(true)
        val window = dialog?.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setGravity(Gravity.START or Gravity.TOP)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.attributes = window.attributes.apply {
            windowAnimations = R.style.NavigationSearchPanelAnimation
        }
        window.decorView.post {
            val visibleFrame = Rect()
            requireActivity().window.decorView.getWindowVisibleDisplayFrame(visibleFrame)
            val metrics = resources.displayMetrics
            val availableWidth = visibleFrame.width().takeIf { it > 0 } ?: metrics.widthPixels
            val availableHeight = visibleFrame.height().takeIf { it > 0 } ?: metrics.heightPixels
            val preferredWidth = (availableWidth * if (availableWidth > availableHeight) 0.42f else 0.92f)
                .toInt()
            window.setLayout(
                preferredWidth.coerceIn(dp(320), dp(440)).coerceAtMost(availableWidth),
                availableHeight
            )
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        parentFragmentManager.setFragmentResult(REQUEST_PANEL_CLOSED, Bundle.EMPTY)
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        destinationSearchRunnable?.let(destinationInput::removeCallbacks)
        destinationSearchRunnable = null
        super.onDestroyView()
    }

    private fun submitDestination() {
        val destination = destinationInput.text.toString().trim()
        if (destination.isEmpty()) {
            destinationInput.error = "Enter a destination"
            return
        }
        when (naviViewModel.setRoute(destination)) {
            NavigationResultCode.ACCEPTED -> dismiss()
            NavigationResultCode.LOCATION_UNAVAILABLE -> showLocationUnavailable()
            else -> showUnavailable()
        }
    }

    private fun searchNearby(category: NearbyCategory) {
        setActiveCategory(category)
        closePlaceDetail()
        naviViewModel.searchNearby(categoryCode = category.code)
    }

    private fun setActiveCategory(category: NearbyCategory) {
        categoryButtons.forEach { (candidate, button) ->
            button.isSelected = candidate == category
        }
    }

    private fun observeState() {
        naviViewModel.suggestions.observe(viewLifecycleOwner, ::renderSuggestions)
        naviViewModel.navigationState.observe(viewLifecycleOwner) { state ->
            navigationState.text = buildString {
                append(state.status.name.replace('_', ' '))
                state.message?.takeIf { it.isNotBlank() }?.let {
                    append(" · ")
                    append(it)
                }
            }
        }
        naviViewModel.searchInProgress.observe(viewLifecycleOwner) {
            searchProgress.visibility = if (it) View.VISIBLE else View.GONE
        }
        naviViewModel.searchMessage.observe(viewLifecycleOwner) {
            searchMessage.text = it.orEmpty()
            searchMessage.visibility = if (it.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        naviViewModel.home.observe(viewLifecycleOwner) {
            navigateHome.isEnabled = it != null
            clearHome.isEnabled = it != null
        }
        naviViewModel.work.observe(viewLifecycleOwner) {
            navigateWork.isEnabled = it != null
            clearWork.isEnabled = it != null
        }
    }

    private fun renderSuggestions(suggestions: List<NavigationSuggestion>) {
        suggestionList.removeAllViews()
        suggestions.forEach { suggestion ->
            val container = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                isClickable = true
                isFocusable = true
                setBackgroundColor(Color.WHITE)
                setOnClickListener { showPlaceDetail(suggestion) }
            }
            container.addView(TextView(requireContext()).apply {
                text = suggestion.name
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(15, 23, 42))
                textSize = 13f
                maxLines = 1
            })
            container.addView(TextView(requireContext()).apply {
                text = suggestion.summaryText()
                setTextColor(Color.rgb(71, 85, 105))
                textSize = 11f
                maxLines = 2
            })
            suggestionList.addView(container)
            suggestionList.addView(
                View(requireContext()).apply { setBackgroundColor(Color.rgb(226, 232, 240)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            )
        }
    }

    private fun showPlaceDetail(suggestion: NavigationSuggestion) {
        selectedSuggestion = suggestion
        placeDetailName.text = suggestion.name
        placeDetailMeta.text = suggestion.summaryText()
        placeDetailAddress.text = suggestion.address?.takeIf { it.isNotBlank() }
            ?: "Address unavailable"
        loadPlacePhoto(suggestion)
        suggestionScroll.visibility = View.GONE
        placeDetail.visibility = View.VISIBLE
    }

    private fun closePlaceDetail() {
        selectedSuggestion = null
        placeDetailPhoto.setImageDrawable(null)
        placeDetailPhoto.visibility = View.GONE
        placeDetail.visibility = View.GONE
        suggestionScroll.visibility = View.VISIBLE
    }

    private fun navigateToSuggestion(suggestion: NavigationSuggestion) {
        when (naviViewModel.selectSuggestion(suggestion.suggestionId)) {
            NavigationResultCode.ACCEPTED -> dismiss()
            NavigationResultCode.LOCATION_UNAVAILABLE -> showLocationUnavailable()
            NavigationResultCode.INVALID_STATE,
            NavigationResultCode.NOT_FOUND -> Toast.makeText(
                requireContext(),
                "Suggestion is no longer available",
                Toast.LENGTH_SHORT
            ).show()
            else -> showUnavailable()
        }
    }

    private fun loadPlacePhoto(suggestion: NavigationSuggestion) {
        val photoUrl = suggestion.photoUrl
        if (photoUrl.isNullOrBlank()) {
            placeDetailPhoto.visibility = View.GONE
            return
        }
        placeDetailPhoto.visibility = View.INVISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL(photoUrl).openConnection() as HttpURLConnection
                    connection.connectTimeout = PHOTO_TIMEOUT_MILLIS
                    connection.readTimeout = PHOTO_TIMEOUT_MILLIS
                    try {
                        connection.inputStream.use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                    } finally {
                        connection.disconnect()
                    }
                }.getOrNull()
            }
            if (selectedSuggestion?.suggestionId == suggestion.suggestionId && bitmap != null) {
                placeDetailPhoto.setImageBitmap(bitmap)
                placeDetailPhoto.visibility = View.VISIBLE
            } else {
                placeDetailPhoto.visibility = View.GONE
            }
        }
    }

    private fun handleNavigationResult(result: Int, isHome: Boolean) {
        when (result) {
            NavigationResultCode.ACCEPTED -> dismiss()
            NavigationResultCode.HOME_NOT_CONFIGURED,
            NavigationResultCode.WORK_NOT_CONFIGURED -> Toast.makeText(
                requireContext(),
                if (isHome) "Home is not configured" else "Work is not configured",
                Toast.LENGTH_SHORT
            ).show()
            NavigationResultCode.LOCATION_UNAVAILABLE -> showLocationUnavailable()
            else -> showUnavailable()
        }
    }

    private fun NavigationSuggestion.summaryText(): String {
        val categoryText = category?.name
            ?.lowercase(Locale.US)
            ?.replace('_', ' ')
            ?.replaceFirstChar { it.titlecase(Locale.US) }
            ?: "Place"
        val distanceText = distanceMeters?.let {
            if (it >= 1_000.0) {
                String.format(Locale.US, "%.1f km", it / 1_000.0)
            } else {
                String.format(Locale.US, "%.0f m", it)
            }
        } ?: "Distance unavailable"
        val ratingText = rating?.let {
            val reviews = reviewCount?.let { count -> " ($count)" }.orEmpty()
            String.format(Locale.US, "%.1f%s", it, reviews)
        } ?: "No rating"
        return "$categoryText · $distanceText · $ratingText"
    }

    private fun showLocationUnavailable() {
        Toast.makeText(
            requireContext(),
            "Location unavailable. Choose the map center as the demo origin.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun showUnavailable() {
        Toast.makeText(requireContext(), "Navigation is unavailable", Toast.LENGTH_SHORT).show()
    }

    private fun requestMapCenter(target: String) {
        parentFragmentManager.setFragmentResult(
            REQUEST_USE_MAP_CENTER,
            Bundle().apply { putString(KEY_MAP_CENTER_TARGET, target) }
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val TAG = "NavigationSearchPanel"
        const val REQUEST_USE_MAP_CENTER = "request_use_map_center"
        const val REQUEST_PANEL_CLOSED = "request_search_panel_closed"
        const val KEY_MAP_CENTER_TARGET = "map_center_target"
        const val TARGET_ORIGIN = "origin"
        const val TARGET_HOME = "home"
        const val TARGET_WORK = "work"

        private const val ARG_CATEGORY_CODE = "category_code"
        private const val NO_INITIAL_CATEGORY = -1
        private const val MIN_AUTOCOMPLETE_LENGTH = 2
        private const val AUTOCOMPLETE_DELAY_MILLIS = 350L
        private const val PHOTO_TIMEOUT_MILLIS = 5_000

        fun newInstance(category: NearbyCategory? = null): NavigationSearchDialogFragment {
            return NavigationSearchDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_CATEGORY_CODE, category?.code ?: NO_INITIAL_CATEGORY)
                }
            }
        }
    }
}
