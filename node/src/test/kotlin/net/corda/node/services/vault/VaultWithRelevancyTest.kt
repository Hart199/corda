package net.corda.node.services.vault

import net.corda.contracts.asset.Cash
import net.corda.contracts.asset.DUMMY_CASH_ISSUER
import net.corda.contracts.asset.DUMMY_CASH_ISSUER_KEY
import net.corda.core.contracts.ContractState
import net.corda.core.node.services.Vault
import net.corda.core.node.services.VaultQueryService
import net.corda.core.node.services.VaultService
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.Relevancy
import net.corda.core.node.services.vault.builder
import net.corda.finance.DOLLARS
import net.corda.finance.USD
import net.corda.node.utilities.CordaPersistence
import net.corda.schemas.CashSchemaV1
import net.corda.testing.*
import net.corda.testing.contracts.fillWithSomeTestCash
import net.corda.testing.contracts.fillWithSomeTestDeals
import net.corda.testing.contracts.fillWithSomeTestLinearStates
import net.corda.testing.node.MockServices
import net.corda.testing.node.makeTestDatabaseAndMockServices
import org.assertj.core.api.Assertions
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.*

class VaultWithRelevancyTest : TestDependencyInjectionBase() {
    private lateinit var services: MockServices
    private lateinit var notaryServices: MockServices
    private val vaultQuerySvc: VaultQueryService get() = services.vaultQueryService
    private lateinit var database: CordaPersistence

    @Before
    fun setUp() {
        val databaseAndServices = makeTestDatabaseAndMockServices(keys = listOf(MEGA_CORP_KEY, DUMMY_NOTARY_KEY), storeIrrelevantStates = true)
        database = databaseAndServices.first
        services = databaseAndServices.second
        notaryServices = MockServices(DUMMY_NOTARY_KEY, DUMMY_CASH_ISSUER_KEY, BOC_KEY, MEGA_CORP_KEY)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `unconsumed states simple with different relevancy`() {
        database.transaction {
            services.fillWithSomeTestCash(100.DOLLARS, notaryServices, DUMMY_NOTARY, 3, 3, Random(0L))
            services.fillWithSomeTestLinearStates(10)
            // Create some irrelevant states.
            services.fillWithSomeTestDeals(listOf("123", "456", "789"), relevantToMe = false)
        }
        database.transaction {
            val irrelevantStates = vaultQuerySvc.queryBy<ContractState>(QueryCriteria.VaultQueryCriteria(relevancy = Relevancy.IRRELEVANT))
            Assertions.assertThat(irrelevantStates.states).hasSize(3)
            Assertions.assertThat(irrelevantStates.statesMetadata).hasSize(3)

            val relevantStates = vaultQuerySvc.queryBy<ContractState>(QueryCriteria.VaultQueryCriteria(relevancy = Relevancy.RELEVANT))
            Assertions.assertThat(relevantStates.states).hasSize(13)
            Assertions.assertThat(relevantStates.statesMetadata).hasSize(13)

            val allStates = vaultQuerySvc.queryBy<ContractState>(QueryCriteria.VaultQueryCriteria(relevancy = Relevancy.ALL))
            Assertions.assertThat(allStates.states).hasSize(16)
            Assertions.assertThat(allStates.statesMetadata).hasSize(16)
        }
    }

    @Test
    fun `unconsumed cash balance for single currency with different relevancy`() {
        database.transaction {
            // Create some irrelevant states.
            services.fillWithSomeTestCash(100.DOLLARS, notaryServices, DUMMY_NOTARY, 1, 1, Random(0L), ownedBy = DUMMY_CASH_ISSUER.party)
            services.fillWithSomeTestCash(200.DOLLARS, notaryServices, DUMMY_NOTARY, 2, 2, Random(0L), ownedBy = DUMMY_CASH_ISSUER.party)
            // Create some relevant states.
            services.fillWithSomeTestCash(300.DOLLARS, notaryServices, DUMMY_NOTARY, 3, 3, Random(0L))
            services.fillWithSomeTestCash(400.DOLLARS, notaryServices, DUMMY_NOTARY, 4, 4, Random(0L))
        }
        val sum = builder { CashSchemaV1.PersistentCashState::pennies.sum(groupByColumns = listOf(CashSchemaV1.PersistentCashState::currency)) }
        val ccyIndex = builder { CashSchemaV1.PersistentCashState::currency.equal(USD.currencyCode) }

        database.transaction {
            val relevantSumCriteria = QueryCriteria.VaultCustomQueryCriteria(sum)
            val relevantCcyCriteria = QueryCriteria.VaultCustomQueryCriteria(ccyIndex)
            val relevantCashStates = vaultQuerySvc.queryBy<Cash.State>(relevantSumCriteria.and(relevantCcyCriteria))
            Assertions.assertThat(relevantCashStates.otherResults).hasSize(2)
            Assertions.assertThat(relevantCashStates.otherResults[0]).isEqualTo(70000L)
            Assertions.assertThat(relevantCashStates.otherResults[1]).isEqualTo("USD")

            val irrelevantSumCriteria = QueryCriteria.VaultCustomQueryCriteria(sum, relevancy = Relevancy.IRRELEVANT)
            val irrelevantCcyCriteria = QueryCriteria.VaultCustomQueryCriteria(ccyIndex, relevancy = Relevancy.IRRELEVANT)
            val irrelevantCashStates = vaultQuerySvc.queryBy<Cash.State>(irrelevantSumCriteria.and(irrelevantCcyCriteria))
            Assertions.assertThat(irrelevantCashStates.otherResults).hasSize(2)
            Assertions.assertThat(irrelevantCashStates.otherResults[0]).isEqualTo(30000L)
            Assertions.assertThat(irrelevantCashStates.otherResults[1]).isEqualTo("USD")

            val allSumCriteria = QueryCriteria.VaultCustomQueryCriteria(sum, relevancy = Relevancy.ALL)
            val allCcyCriteria = QueryCriteria.VaultCustomQueryCriteria(ccyIndex, relevancy = Relevancy.ALL)
            val allCashStates = vaultQuerySvc.queryBy<Cash.State>(allSumCriteria.and(allCcyCriteria))
            Assertions.assertThat(allCashStates.otherResults).hasSize(2)
            Assertions.assertThat(allCashStates.otherResults[0]).isEqualTo(100000L)
            Assertions.assertThat(allCashStates.otherResults[1]).isEqualTo("USD")
        }
    }
}