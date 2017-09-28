package com.template

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.Contract
import net.corda.core.contracts.ContractState
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.node.CordaPluginRegistry
import net.corda.core.serialization.SerializationCustomization
import net.corda.core.transactions.LedgerTransaction
import net.corda.webserver.services.WebServerPluginRegistry
import java.util.function.Function
import javax.ws.rs.GET
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response

// *****************
// * API Endpoints *
// *****************
@Path("template")
class TemplateApi(val services: CordaRPCOps) {
    // Accessible at /api/template/templateGetEndpoint.
    @GET
    @Path("templateGetEndpoint")
    @Produces(MediaType.APPLICATION_JSON)
    fun templateGetEndpoint(): Response {
        return Response.ok(mapOf("message" to "Template GET endpoint.")).build()
    }
}

// *****************
// * Contract Code *
// *****************
/* open class TemplateContract : Contract {
    // The verify() function of the contract for each of the transaction's input and output states must not throw an
    // exception for a transaction to be considered valid.
    override fun verify(tx: LedgerTransaction) {
        // Verification logic goes here.
    }

    // A reference to the underlying legal contract template and associated parameters.
    override val legalContractReference: SecureHash = SecureHash.zeroHash
}*/



class BetContract : Contract {
    
    interface Commands : CommandData {
        class Create : TypeOnlyCommandData(), Commands
    }

    
    override fun verify(tx: LedgerTransaction) {
         
         val command = tx.commands.requireSingleCommand<Commands.Create>()

         requireThat {
            "No inputs should be consumed when issuing an IOU." using (tx.inputs.isEmpty())
            "Only one output state should be created." using (tx.outputs.size == 1)
            val out = tx.outputsOfType<Bet>().single()
            "PartyA and PartyB must be different" using (out.partyA != out.partyB)
            "The bet ammount must be non-negative." using (out.iou.ammount > 0)
         }
    }

    override val legalContractReference: SecureHash = SecureHash.sha256("You bet")
}

// *********
// * State *
// *********
/*class TemplateState(val data: String) : ContractState {
    override val participants: List<AbstractParty> get() = listOf()
    override val contract: TemplateContract get() = TemplateContract()
}*/

data class Bet(val partyA: Party, val partyB: Party, val ammount: Int, override val linearId: UniqueIdentifier = UniqueIdentifier()) : LinearState {

    // Contract state overriding
    override val participants: List<AbstractParty> get() = listOf(partyA, partyB)
    override val contract: BetContract get() = BetContract() // TODO

    // Overriding needed? let's see
    override fun withNewOwner(newOwner: AbstractParty) = CommandAndState(Commands.Move(), copy(owner = newOwner))
    override fun toString() = "${Emoji.unicorn} $partyA and $partyB are betting hard: $ammount"

    // Tells the vault to track a state if we are one of the parties involved. 
    override fun isRelevant(ourKeys: Set<PublicKey>) = ourKeys.intersect(participants.flatMap { it.owningKey.keys }).isNotEmpty()
}


// *********
// * Flows *
// *********
@InitiatingFlow
@StartableByRPC
class BettingProcess(val otherParty, val valueToBet: Int) : FlowLogic<SignedTransaction>() {

    companion object {
        object GENERATING_TRANSACTION : Step("Generating transaction based on new IOU.")
        object VERIFYING_TRANSACTION : Step("Verifying contract constraints.")
        object SIGNING_TRANSACTION : Step("Signing transaction with our private key.")
        object GATHERING_SIGS : Step("Gathering the counterparty's signature.") {
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }

        object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
            override fun childProgressTracker() = FinalityFlow.tracker()
        }

        fun tracker() = ProgressTracker(
                GENERATING_TRANSACTION,
                VERIFYING_TRANSACTION,
                SIGNING_TRANSACTION,
                GATHERING_SIGS,
                FINALISING_TRANSACTION
        )
    }

    override val progressTracker

    @Suspendable
    override fun call() {
        // Obtain a reference to the notary we want to use.
        val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity

        progressTracker.currentStep = GENERATING_TRANSACTION
        val betState = Bet(serviceHub.myInfo.legalIdentity, otherParty, valueToBet)
        val cmd = Command(BetContract.Commands.Create(), betState.participants.map{ it.owningKey })
        val txBuilder = TransactionBuilder(TransactionType.General, notary).withItems(betState, cmd)

        progressTracker.currentStep = VERIFYING_TRANSACTION

        // WireTransaction = transaction without any signature attached
        txBuilder.toWireTransaction().toLedgerTransaction(serviceHub).verify()

        progressTracker.currentStep = SIGNING_TRANSACTION

        val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

        progressTracker.currentStep = GATHERING_SIGS

        val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, GATHERING_SIGS.childProgressTracker()))

        progressTracker.currentStep = FINALISING_TRANSACTION

        return subFlow(FinalityFlow(fullySignedTx, FINALISING_TRANSACTION.childProgressTracker())).single()
    }
}

@InitiatedBy(Initiator::class)
class Responder(val otherParty: Party) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call() : SignedTransaction {
        val signTransactionFlow = object : SignTransactionFlow(otherParty) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an IOU transaction"
            }
        }
    }
}

// *******************
// * Plugin Registry *
// *******************
class TemplatePlugin : CordaPluginRegistry() {
    // Whitelisting the required types for serialisation by the Corda node.
    override fun customizeSerialization(custom: SerializationCustomization): Boolean {
        return true
    }
}

class TemplateWebPlugin : WebServerPluginRegistry {
    // A list of classes that expose web JAX-RS REST APIs.
    override val webApis: List<Function<CordaRPCOps, out Any>> = listOf(Function(::TemplateApi))
    //A list of directories in the resources directory that will be served by Jetty under /web.
    // This template's web frontend is accessible at /web/template.
    override val staticServeDirs: Map<String, String> = mapOf(
            // This will serve the templateWeb directory in resources to /web/template
            "template" to javaClass.classLoader.getResource("templateWeb").toExternalForm()
    )
}
