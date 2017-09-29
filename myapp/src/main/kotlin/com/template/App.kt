package com.template

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.node.CordaPluginRegistry
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.flows.CollectSignaturesFlow
import net.corda.core.serialization.SerializationCustomization
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.flows.FinalityFlow
import net.corda.webserver.services.WebServerPluginRegistry
import java.util.function.Function
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step
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
class OwnershipContract : Contract {

    // *********
    // * State *
    // *********
    data class Ownership(val owner: AbstractParty, val liquidity: Int) : ContractState {
        override val participants: List<AbstractParty> get() = listOf(owner)
        override val contract: OwnershipContract get() = OwnershipContract()


        // override fun withNewOwner(newOwner: AbstractParty) = CommandAndState(Commands.Move(), copy(owner = newOwner))
    }

    
    // ************
    // * Commands *
    // ************
    interface Commands : CommandData {
        class Issue : TypeOnlyCommandData(), Commands
        class Move : TypeOnlyCommandData(), Commands
    }

    // The verify() function of the contract for each of the transaction's input and output states must not throw an
    // exception for a transaction to be considered valid.
    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands.Issue>()

        requireThat {
            "No inputs should be consumed when issue cash." using (tx.inputs.isEmpty())
            "Only one output state should be created." using (tx.outputs.size == 1)
            val output = tx.outputsOfType<Ownership>().single()
            "All of the participants must be signers." using 
                (command.signers.containsAll(output.participants.map { it.owningKey }))

            "The liquidity must be more than 0." using (output.liquidity > 0)
        }
    }

    // A reference to the underlying legal contract template and associated parameters.
    override val legalContractReference: SecureHash = SecureHash.zeroHash
}






// *********
// * Flows *
// *********
@InitiatingFlow
@StartableByRPC
class Issue(val value: Int) : FlowLogic<SignedTransaction>() {
    companion object {
        object GENERATING_TX : Step("Generating the transaction.")
        object VERIFYING_TX : Step("Verifying the transaction.")
        object SIGNING_TX : Step("Signing the transaction.")
        object PROCESSING_TX : Step("Processing the transaction.")
        object FINALISING_TX : Step("Finalising the transaction.")

        // We do it in this way to enable other flow to access this freaking fields
        fun tracker() = ProgressTracker(GENERATING_TX, VERIFYING_TX, 
            SIGNING_TX, FINALISING_TX)
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call(): SignedTransaction {
        val notary = serviceHub.networkMapCache.notaryNodes.single().notaryIdentity

        progressTracker.currentStep = GENERATING_TX
        val ownership = OwnershipContract.Ownership(serviceHub.myInfo.legalIdentity, value)
        val cmd = Command(OwnershipContract.Commands.Issue(), ownership.participants.map{ it.owningKey })
        val builder = TransactionBuilder(TransactionType.General, notary).withItems(ownership, cmd)

        progressTracker.currentStep = VERIFYING_TX
        builder.toWireTransaction().toLedgerTransaction(serviceHub).verify()

        progressTracker.currentStep = SIGNING_TX
        

        val issueTx = serviceHub.signInitialTransaction(builder)

        serviceHub.recordTransactions(issueTx)

        return issueTx

        
        
    }
}

/*@InitiatedBy(Initiator::class)
class Responder(val otherParty: Party) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        return Unit
    }
}*/

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
