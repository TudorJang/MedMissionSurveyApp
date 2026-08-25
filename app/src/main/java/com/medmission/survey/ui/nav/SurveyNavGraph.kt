package com.medmission.survey.ui.nav

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.medmission.survey.data.network.UnauthorizedException
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.medmission.survey.SurveyApplication
import com.medmission.survey.ui.form.FormScreen
import com.medmission.survey.ui.form.FormViewModel
import com.medmission.survey.ui.home.HomeScreen
import com.medmission.survey.ui.home.HomeViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.medmission.survey.data.settings.FormMode
import com.medmission.survey.ui.laptopselect.LaptopSelectScreen
import com.medmission.survey.ui.settings.SettingsScreen
import com.medmission.survey.util.formatCellPhoneInput
import com.medmission.survey.ui.laptopselect.LaptopSelectViewModel
import kotlinx.coroutines.launch

@Composable
fun SurveyNavGraph(navController: NavHostController = rememberNavController()) {
    val app = LocalContext.current.applicationContext as SurveyApplication

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            val viewModel: HomeViewModel = viewModel(
                factory = viewModelFactory { initializer { HomeViewModel(app.surveyRepository) } },
            )
            val records by viewModel.records.collectAsState()
            val xrayStatuses by viewModel.xrayStatuses.collectAsState()
            HomeScreen(
                records = records,
                xrayStatuses = xrayStatuses,
                onNewSurvey = { navController.navigate("form") },
                onRecordClick = { recordId -> navController.navigate("form?recordId=$recordId") },
                // Straight back to laptop selection: that screen already holds the key
                // field, which is what the operator had to fix before trying again.
                onResend = { recordId -> navController.navigate("laptopSelect/$recordId") },
                onSettings = { navController.navigate("settings") },
            )
        }
        composable(
            "form?recordId={recordId}",
            arguments = listOf(navArgument("recordId") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { backStackEntry ->
            val recordId = backStackEntry.arguments?.getString("recordId")
            // Scoped to the back-stack entry's ViewModelStore, so a rotation does not
            // rebuild FormViewModel and mint a fresh UUID for an in-progress draft.
            val viewModel: FormViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        FormViewModel(app.surveyRepository, recordId, app.devicePrefix,
                            country = app.appSettings.effectiveCountryCode)
                    }
                },
            )
            LaunchedEffect(recordId) {
                if (recordId != null) viewModel.load()
            }
            val record by viewModel.record.collectAsState()
            FormScreen(
                record = record,
                onFieldChange = { viewModel.updateField(it) },
                onToggleMedicalHistory = { viewModel.toggleMedicalHistory(it) },
                onToggleSymptom = { viewModel.toggleSymptom(it) },
                onDone = { navController.navigate("laptopSelect/${record.recordId}") },
                // Backing out takes the placeholder row with it when nothing was typed,
                // so a tapped-by-accident New Survey does not leave a blank in the list
                // or consume a patient number.
                onCancel = { viewModel.discardIfUntouched { navController.popBackStack() } },
                psgcRepository = app.psgcRepository,
                formMode = app.appSettings.formMode,
                // The global form formats numbers for the country being screened; the
                // Philippine form keeps the mask it has always had.
                formatPhone = if (app.appSettings.formMode == FormMode.GLOBAL) {
                    { typed -> app.phoneFormatter.formatAsYouType(typed, app.appSettings.effectiveCountryCode) }
                } else ::formatCellPhoneInput,
            )
        }
        composable("settings") {
            var mode by remember { mutableStateOf(app.appSettings.formMode) }
            var country by remember { mutableStateOf(app.appSettings.countryCode) }
            SettingsScreen(
                formMode = mode,
                countryCode = country,
                onFormModeChange = { app.appSettings.formMode = it; mode = it },
                onCountryChange = { app.appSettings.countryCode = it; country = it },
                onDone = { navController.popBackStack() },
            )
        }
        composable(
            "laptopSelect/{recordId}",
            arguments = listOf(navArgument("recordId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val recordId = backStackEntry.arguments?.getString("recordId")!!
            val viewModel: LaptopSelectViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        LaptopSelectViewModel(
                            app.laptopEndpointRepository,
                            app.nsdDiscoveryService,
                            app.surveyRepository,
                            recordId,
                        )
                    }
                },
            )
            val saved by viewModel.savedEndpoints.collectAsState()
            val discovered by viewModel.discovered.collectAsState()
            val scope = rememberCoroutineScope()
            val context = LocalContext.current
            val priorSend by viewModel.priorSend.collectAsState()
            LaptopSelectScreen(
                savedEndpoints = saved,
                discoveredLaptops = discovered,
                priorSend = priorSend,
                onSelect = { laptopId ->
                    scope.launch {
                        val result = viewModel.send(laptopId)
                        // On failure the record stays PENDING and SurveyRetryWorker will
                        // pick it up, so we still return Home — but the user gets told.
                        // A rejected key never comes good on its own, so promising a
                        // retry would be a lie and the record is already FAILED.
                        val message = when {
                            result.isSuccess -> "Sent"
                            result.exceptionOrNull() is UnauthorizedException ->
                                "Laptop rejected the API key — check it on this laptop's page"
                            else -> "Send failed — will retry automatically"
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        navController.popBackStack("home", inclusive = false)
                    }
                },
                onAddManual = { name, host, port, apiKey ->
                    scope.launch { viewModel.addManualEndpoint(name, host, port, apiKey) }
                },
                onApiKeyChange = { laptopId, apiKey ->
                    scope.launch { viewModel.updateApiKey(laptopId, apiKey) }
                },
            )
        }
    }
}
