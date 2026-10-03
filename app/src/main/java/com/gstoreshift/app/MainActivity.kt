package com.gstoreshift.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gstoreshift.app.account.GoogleAccountManager
import com.gstoreshift.app.ui.MainViewModel
import com.gstoreshift.app.ui.QuotaInfo
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i("GSTOR_TEST", "APP_LAUNCHED pkg=$packageName")
        setContent { MaterialTheme { AppRoot() } }
    }

    @Composable
    fun AppRoot(vm: MainViewModel = viewModel()) {
        val aux by vm.auxAccounts.collectAsState()
        val rollup by vm.monthRollup.collectAsState()
        val scanning by vm.scanning.collectAsState()
        val host by vm.hostEmail.collectAsState()
        val quotas by vm.quotas.collectAsState()
        val message by vm.message.collectAsState()
        val refreshing by vm.refreshingQuotas.collectAsState()
        val snackbar = remember { SnackbarHostState() }

        val signInClient = remember { GoogleAccountManager(this@MainActivity).client() }

        val hostSignIn = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    .getResult(ApiException::class.java)
                account.email?.let { vm.onHostSignedIn(it) }
            } catch (e: ApiException) {
                vm.reportSignInError("host", "code ${e.statusCode}. Check OAuth client & SHA-1.")
            }
        }

        val auxSignIn = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    .getResult(ApiException::class.java)
                account.email?.let { vm.onAuxSignedIn(it) }
            } catch (e: ApiException) {
                vm.reportSignInError("aux", "code ${e.statusCode}. Check OAuth client & SHA-1.")
            }
        }

        LaunchedEffect(message) {
            message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
        }

        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text("GStoreShift", style = MaterialTheme.typography.headlineMedium)
                }

                // ---- Host account ----
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Host account (nearly full)", style = MaterialTheme.typography.titleMedium)
                            if (host == null) {
                                Text("Not signed in.")
                                Button(onClick = { hostSignIn.launch(signInClient.signInIntent) }) {
                                    Text("Sign in host account")
                                }
                            } else {
                                Text(host!!)
                                QuotaBar(quotas[host])
                            }
                        }
                    }
                }

                // ---- Aux accounts ----
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Auxiliary accounts", style = MaterialTheme.typography.titleMedium)
                            if (aux.filter { it.email != host }.isEmpty()) Text("No auxiliary accounts yet.")
                            aux.filter { it.email != host }.forEach { acc ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text(acc.email)
                                        QuotaBar(quotas[acc.email])
                                    }
                                    TextButton(onClick = { vm.removeAux(acc.email) }) { Text("Remove") }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { auxSignIn.launch(signInClient.signInIntent) }) {
                                    Text("Add aux account")
                                }
                                OutlinedButton(onClick = { vm.refreshQuotas() }, enabled = !refreshing) {
                                    Text(if (refreshing) "Refreshing…" else "Refresh quotas")
                                }
                            }
                        }
                    }
                }

                // ---- Device media ----
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Device media", style = MaterialTheme.typography.titleMedium)
                            Button(onClick = { vm.runScan() }, enabled = !scanning) {
                                Text(if (scanning) "Scanning…" else "Scan photos & videos")
                            }
                            rollup.take(12).forEach { r ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("%04d-%02d — %d files (%.1f GB)".format(r.year, r.month, r.cnt, r.bytes / 1e9))
                                    TextButton(onClick = { vm.queueMonth(r.year, r.month) }) { Text("Queue") }
                                }
                            }
                            if (rollup.size > 12) Text("… ${rollup.size - 12} more months")
                        }
                    }
                }

                // ---- Migration ----
                item {
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Migration", style = MaterialTheme.typography.titleMedium)
                            Button(onClick = { vm.startMigration(wifiOnly = true) }) {
                                Text("Start migration (Wi-Fi only)")
                            }
                            Text(
                                "After uploads are verified, you'll be asked to approve local cleanup.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun QuotaBar(q: QuotaInfo?) {
        if (q == null || q.total <= 0) {
            Text("quota unknown — tap Refresh quotas", style = MaterialTheme.typography.bodySmall)
            return
        }
        val usedFrac = (q.used.toFloat() / q.total).coerceIn(0f, 1f)
        LinearProgressIndicator(progress = { usedFrac }, modifier = Modifier.fillMaxWidth())
        Text(
            "%.1f GB free of %.1f GB (%.0f%% used)".format(
                q.free / 1e9, q.total / 1e9, usedFrac * 100
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
