package com.medmission.survey.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
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
            val viewModel = remember { HomeViewModel(app.surveyRepository) }
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
            val viewModel = remember(recordId) { FormViewModel(app.surveyRepository, recordId) }
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
            val viewModel = remember(recordId) {
                LaptopSelectViewModel(app.laptopEndpointRepository, app.nsdDiscoveryService, app.surveyRepository, recordId)
            }
            val saved by viewModel.savedEndpoints.collectAsState()
            val scope = rememberCoroutineScope()
            LaptopSelectScreen(
                savedEndpoints = saved,
                onSelect = { laptopId ->
                    scope.launch {
                        viewModel.send(laptopId)
                        navController.popBackStack("home", inclusive = false)
                    }
                },
                onAddManual = { /* opens a dialog — left to a follow-up UI-polish task */ },
            )
        }
    }
}
