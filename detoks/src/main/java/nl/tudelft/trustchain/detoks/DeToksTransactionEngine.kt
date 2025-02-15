package nl.tudelft.trustchain.detoks

import android.content.Context
import android.util.Log
import nl.tudelft.ipv8.Overlay
import nl.tudelft.ipv8.Peer
import nl.tudelft.ipv8.android.keyvault.AndroidCryptoProvider
import nl.tudelft.ipv8.attestation.trustchain.*
import nl.tudelft.ipv8.attestation.trustchain.store.TrustChainStore
import nl.tudelft.trustchain.detoks.db.TokenStore
import java.util.UUID

/**
 * Handles the sending and receiving of transactions
 */
class DeToksTransactionEngine (
    val tokenStore: TokenStore,
    val context: Context,
    settings: TrustChainSettings,
    database: TrustChainStore,
    crawler: TrustChainCrawler
) : TrustChainCommunity(settings, database, crawler) {

    override val serviceId = "12313685c1912a191279f8248fc8db5899c5df6a"

    private val SINGLE_BLOCK = "SingleBlock3"
    private val GROUPED_BLOCK = "GroupedBlock3"

    private val LOGTAG = "DeToksTransactionEngine"

    private lateinit var selectedPeer : Peer
    private lateinit var selfPeer : Peer
    private var sendingToSelf = true

    public var totalTimeTracker = 0L

    init {
        // Set up block listeners
        addListener(SINGLE_BLOCK, object : BlockListener {
            override fun onBlockReceived(block: TrustChainBlock) {
                // If the block is actually addressed to me, or if I'm sending to myself, process the block
                if (sendingToSelf || AndroidCryptoProvider.keyFromPublicBin(block.linkPublicKey) == selfPeer.publicKey) {
                    if (block.isProposal) {
                        receiveSingleTokenProposal(block)
                    } else if (block.isAgreement) {
                        receiveSingleTokenAgreement(block)
                    }
                }
            }
        })

        addListener(GROUPED_BLOCK, object : BlockListener {
            override fun onBlockReceived(block: TrustChainBlock) {
                    // If the block is actually addressed to me, or if I'm sending to myself, process the block
                    if (sendingToSelf || AndroidCryptoProvider.keyFromPublicBin(block.linkPublicKey) == selfPeer.publicKey) {
                        if (block.isProposal) {
                            receiveGroupedTokenProposal(block)
                        } else if (block.isAgreement) {
                            receiveGroupedTokenAgreement(block)
                        }
                    }
            }
        })
    }

    /**
     * Send a single token, no grouping
     * @param tok: The token to send
     * @param peer: The peer to send the token to
     */
    fun sendTokenSingle(tok: Token, peer: Peer, resend: Boolean): TrustChainBlock {
        var startBenchmark = System.nanoTime()
        val transaction = mapOf("token" to tok.toString(), "resend" to resend)

        val block = createProposalBlock(
            SINGLE_BLOCK,
            transaction,
            peer.publicKey.keyToBin()
        )
        totalTimeTracker += (System.nanoTime() - startBenchmark)
        return block
    }

    /**
     * Receive a single token proposal, no grouping
     * @param block: The proposal block
     */
    private fun receiveSingleTokenProposal(block: TrustChainBlock) {
        var startBenchmark = System.nanoTime()
        val token = block.transaction["token"] as String
        val resend = block.transaction["resend"] as Boolean
        val (uid, intId) = token.split(",")

        // Add token to personal database
        // If sending to self ->  Increment the ID to avoid duplicate ID errors.
        var newID = uid
        if (sendingToSelf) {
            newID = UUID.randomUUID().toString()
        }
        if(resend){
            tokenStore.addToken(newID, intId.toLong(), true)
        } else{
            tokenStore.addToken(newID, intId.toLong(), false)
        }

        // Create an agreement block to send back
        val transaction = mapOf("tokenSent" to uid)
        createAgreementBlock(block, transaction)
        totalTimeTracker += (System.nanoTime() - startBenchmark)
    }

    /**
     * Receive a single token agreement block and remove token from personal database, no grouping
     * @param block: The agreement block
     */
    private fun receiveSingleTokenAgreement(block: TrustChainBlock) {
        var startBenchmark = System.nanoTime()
        val tokenId = block.transaction["tokenSent"] as String
        // Token was received correctly, remove token from personal database
        tokenStore.removeTokenByID(tokenId)
        totalTimeTracker += (System.nanoTime() - startBenchmark)
    }

    /**
     * Send a list of tokens, grouped
     * @param transactions: List of lists of tokens to be sent
     * @param peer: Peer to send the tokens to
     */
    fun sendTokenGrouped(transactions: List<List<Token>>, peer: Peer, resend: Boolean): TrustChainBlock {
        var startBenchmark = System.nanoTime()
        val groupedTransactions = mutableListOf<List<String>>()
        for (transaction in transactions) {
            val tokenList: MutableList<String> = mutableListOf()
            for (token in transaction) {
                tokenList.add(token.toString())
            }
            groupedTransactions.add(tokenList)
        }
        val transaction = mapOf("transactions" to groupedTransactions, "resend" to resend)

        val block = createProposalBlock(
            GROUPED_BLOCK,
            transaction,
            peer.publicKey.keyToBin()
        )
        totalTimeTracker += (System.nanoTime() - startBenchmark)
        return block
    }

    /**
     * Receive a grouped token proposal
     * @param block: The proposal block
     */
    @Suppress("UNCHECKED_CAST")
    private fun receiveGroupedTokenProposal(block: TrustChainBlock) {
        var startBenchmark = System.nanoTime()
        val transactions = block.transaction["transactions"] as List<List<String>>
        val resend = block.transaction["resend"] as Boolean

        // Extract tokens from transactions field, create grouped agreement block as response
        val grouped_agreement_uids = mutableListOf<List<String>>()
        val tokensToAdd = mutableListOf<Token>()

        for (transaction in transactions) {
            val tokenList: MutableList<String> = mutableListOf()
            for (token in transaction) {
                val (uid, intId) = token.split(",")
                tokenList.add(uid)

                // If sending to self ->  Increment the ID to avoid duplicate ID errors.
                var newID = uid
                //Log.d(LOGTAG, "Sending to self: $sendingToSelf")
                if (sendingToSelf) {
                    newID = UUID.randomUUID().toString()
                }

                // Add token to personal database
                tokensToAdd.add(Token(newID, intId.toInt()))
            }
            grouped_agreement_uids.add(tokenList.toList())
        }
        // If we resend we need to check first that token is not in the database so we don't add it again
        if(resend){
            tokenStore.addTokenList(tokensToAdd, true)
        } else{
            tokenStore.addTokenList(tokensToAdd, false)
        }
        val transaction = mapOf("tokensSent" to grouped_agreement_uids.toList())
        createAgreementBlock(block, transaction)
        totalTimeTracker += (System.nanoTime() - startBenchmark)
    }

    /**
     * Receive a grouped token agreement block and remove tokens from personal database
     * @param block: The agreement block
     */
    @Suppress("UNCHECKED_CAST")
    private fun receiveGroupedTokenAgreement(block: TrustChainBlock) {
        var startBenchmark = System.nanoTime()
        val tokenIds = block.transaction["tokensSent"] as List<List<String>>
        val tokensToRemove= mutableListOf<String>()

        for (tokens in tokenIds ) {
                for(token_id in tokens) {
                    // Remove token from personal database
                    tokensToRemove.add(token_id)
                }
        }
        tokenStore.removeTokenList(tokensToRemove.toList())
        totalTimeTracker += (System.nanoTime() - startBenchmark)
    }

    /**
     * Initializes self and selected peer variables
     * Sets both peers to self as a default starting point
     * @param self: The self peer
     */
    fun initializePeers(self: Peer) {
        selectedPeer = self
        selfPeer = self
    }

    /**
     * Gets the selected peer
     */
    fun getSelectedPeer() : Peer {
        return selectedPeer
    }

    /**
     * Gets the self peer
     */
    fun getSelfPeer() : Peer {
        return selfPeer
    }

    /**
     * Sets the selected peer
     * Also sets the sendingToSelf variable if the selected peer is the self peer
     * @param peer: The peer to set as selected
     */
    fun setPeer(peer: Peer) {
        selectedPeer = peer
        sendingToSelf = peer == selfPeer
        Log.d(LOGTAG, "Selected peer: ${selectedPeer.publicKey}")
    }


    /**
     * Checks if the peers are initialized yet, used in the onViewCreated method of BenchmarkFragment
     */
    fun isPeerSelected() : Boolean {
        return ::selectedPeer.isInitialized
    }

    class Factory(
        private val store: TokenStore,
        private val context: Context,
        private val settings: TrustChainSettings,
        private val database: TrustChainStore,
        private val crawler: TrustChainCrawler = TrustChainCrawler()
    ) : Overlay.Factory<DeToksTransactionEngine>(DeToksTransactionEngine::class.java) {
        override fun create(): DeToksTransactionEngine {
            return DeToksTransactionEngine(store, context, settings, database, crawler)
        }
    }
}

