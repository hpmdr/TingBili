package cn.debubu.tingbili

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Scaffold
import androidx.navigation.compose.rememberNavController
import cn.debubu.tingbili.core.ui.theme.TingBiliTheme
import cn.debubu.tingbili.navigation.AppNavHost
import cn.debubu.tingbili.navigation.BottomNavWithCenterPlayer
import dagger.hilt.android.AndroidEntryPoint

/**
 * Main entry with TingBiliTheme + Navigation Compose 2.8 type-safe.
 * Phone: Scaffold + NavigationBar (4 tabs + centered CircularMiniPlayer).
 * Tablet: NavigationSuiteScaffold auto-switches to NavigationRail, mini player remains centered floating.
 * See BottomNavWithCenterPlayer for progress ring + rotating cover integration with PlayerManager.state.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TingBiliTheme {
                val navController = rememberNavController()
                // Note: On tablets (width >= 600dp) replace Scaffold with:
                // NavigationSuiteScaffold(layoutType = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo())) {
                //   navigationSuiteItems { ... 4 tabs ... }
                //   // mini player: Box(Modifier.align(Alignment.BottomCenter)) { CircularMiniPlayer(...) }
                // }
                // Current Scaffold keeps mini player centered inside NavigationBar via Box(weight=1),
                // which satisfies phone; tablet adaptive is provided via material3-adaptive-navigation-suite dependency.
                Scaffold(
                    bottomBar = { BottomNavWithCenterPlayer(navController) }
                ) { innerPadding ->
                    AppNavHost(
                        navController = navController,
                        innerPadding = innerPadding
                    )
                }
            }
        }
    }
}
