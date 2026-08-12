package com.medmission.survey.ui.nav

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.medmission.survey.ui.laptopselect.LaptopSelectScreen
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
            HomeScreen(
                records = records,
                onNewSurvey = { navController.navigate("form") },
                onRecordClick = { recordId -> navController.navigate("form?recordId=$recordId") },
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
                factory = viewModelFactory { initializer { FormViewModel(app.surveyRepository, recordId) } },
            )
            LaunchedEffect(recordId) {
                if (recordId != null) viewModel.load()
            }
            val record by viewModel.record.collectAsState()
            FormScreen(
                record = record,
                onFirstNameChange = { viewModel.updateField { r -> r.copy(firstName = it) } },
                onLastNameChange = { viewModel.updateField { r -> r.copy(lastName = it) } },
                onToggleMedicalHistory = { viewModel.toggleMedicalHistory(it) },
                onToggleSymptom = { viewModel.toggleSymptom(it) },
                onDone = { navController.navigate("laptopSelect/${record.recordId}") },
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
            LaptopSelectScreen(
                savedEndpoints = saved,
                discoveredLaptops = discovered,
                onSelect = { laptopId ->
                    scope.launch {
                        val result = viewModel.send(laptopId)
                        // On failure the record stays PENDING and SurveyRetryWorker will
                        // pick it up, so we still return Home — but the user gets told.
                        val message =
                            if (result.isSuccess) "Sent" else "Send failed — will retry automatically"
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        navController.popBackStack("home", inclusive = false)
                    }
                },
                onAddManual = { name, host, port ->
                    scope.launch { viewModel.addManualEndpoint(name, host, port) }
                },
            )
        }
    }
}
