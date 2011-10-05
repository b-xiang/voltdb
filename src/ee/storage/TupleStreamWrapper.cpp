/* This file is part of VoltDB.
 * Copyright (C) 2008-2011 VoltDB Inc.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

#include "storage/TupleStreamWrapper.h"

#include "common/TupleSchema.h"
#include "common/types.h"
#include "common/NValue.hpp"
#include "common/ValuePeeker.hpp"
#include "common/tabletuple.h"
#include "common/ExportSerializeIo.h"
#include "common/executorcontext.hpp"

#include <cstdio>
#include <iostream>
#include <cassert>
#include <ctime>
#include <utility>
#include <math.h>
#include <limits>

using namespace std;
using namespace voltdb;

const int METADATA_COL_CNT = 6;
const int MAX_BUFFER_AGE = 4000;

TupleStreamWrapper::TupleStreamWrapper(CatalogId partitionId,
                                       CatalogId siteId)
    : m_partitionId(partitionId), m_siteId(siteId),
      m_lastFlush(0), m_defaultCapacity(EL_BUFFER_SIZE),
      m_uso(0), m_currBlock(NULL),
      m_openTransactionId(0), m_openTransactionUso(0),
      m_committedTransactionId(0), m_committedUso(0),
      m_signature(""), m_generation(numeric_limits<int64_t>::min()),
      m_prevBlockGeneration(numeric_limits<int64_t>::min())
{
    extendBufferChain(m_defaultCapacity);
}

void
TupleStreamWrapper::setDefaultCapacity(size_t capacity)
{
    assert (capacity > 0);
    if (m_uso != 0 || m_openTransactionId != 0 ||
        m_openTransactionUso != 0 || m_committedTransactionId != 0)
    {
        throwFatalException("setDefaultCapacity only callable before "
                            "TupleStreamWrapper is used");
    }
    cleanupManagedBuffers();
    m_defaultCapacity = capacity;
    extendBufferChain(m_defaultCapacity);
}



/*
 * Essentially, shutdown.
 */
void TupleStreamWrapper::cleanupManagedBuffers()
{
    StreamBlock *sb = NULL;

    if (m_currBlock != NULL)
    {
        discardBlock(m_currBlock);
        m_currBlock = NULL;
    }

    while (m_pendingBlocks.empty() != true) {
        sb = m_pendingBlocks.front();
        m_pendingBlocks.pop_front();
        discardBlock(sb);
    }
}


void TupleStreamWrapper::setSignatureAndGeneration(std::string signature, int64_t generation) {
    assert(generation > m_generation);
    assert(signature == m_signature || m_signature == string(""));

    if (generation != m_generation &&
        m_generation != numeric_limits<int64_t>::min())
    {
        commit(generation, generation);
        extendBufferChain(0);
        drainPendingBlocks();
    }
    m_signature = signature;
    m_generation = generation;
}

/*
 * Handoff fully committed blocks to the top end.
 *
 * This is the only function that should modify m_openTransactionId,
 * m_openTransactionUso.
 */
void TupleStreamWrapper::commit(int64_t lastCommittedTxnId, int64_t currentTxnId, bool sync)
{
    if (currentTxnId < m_openTransactionId)
    {
        throwFatalException("Active transactions moving backwards");
    }

    // more data for an ongoing transaction with no new committed data
    if ((currentTxnId == m_openTransactionId) &&
        (lastCommittedTxnId == m_committedTransactionId))
    {
        return;
    }

    // If the current TXN ID has advanced, then we know that:
    // - The old open transaction has been committed
    // - The current transaction is now our open transaction
    if (m_openTransactionId < currentTxnId)
    {
        m_committedUso = m_uso;
        // Advance the tip to the new transaction.
        m_committedTransactionId = m_openTransactionId;
        m_openTransactionId = currentTxnId;
    }

    // now check to see if the lastCommittedTxn tells us that our open
    // transaction should really be committed.  If so, update the
    // committed state.
    if (m_openTransactionId <= lastCommittedTxnId)
    {
        m_committedUso = m_uso;
        m_committedTransactionId = m_openTransactionId;
    }
}

void
TupleStreamWrapper::drainPendingBlocks()
{
    //cout << "PENDING BLOCKS: " << m_pendingBlocks.size() << endl;
    while (!m_pendingBlocks.empty())
    {
        StreamBlock* block = m_pendingBlocks.front();
        //cout << "Checking block: " << block->generationId() << ", "
        //     << block->uso() << ", " << block->offset() << endl;
        //cout << "Previous block generation: " << m_prevBlockGeneration << endl;
        // Check to see if we need to inject an end of stream
        // indication to Java
        if (block->generationId() > m_prevBlockGeneration &&
            m_prevBlockGeneration != numeric_limits<int64_t>::min())
        {
            StreamBlock* eos_block = new StreamBlock(NULL, 0, block->uso());
            eos_block->setGenerationId(m_prevBlockGeneration);
            eos_block->setSignature(m_signature);
            eos_block->setEndOfStream(true);
            pushExportBlock(eos_block);
        }
        m_prevBlockGeneration = block->generationId();

        // check that the entire remainder is committed
        if (m_committedUso >= (block->uso() + block->offset()))
        {
            pushExportBlock(block);
            m_pendingBlocks.pop_front();
        }
        else
        {
            break;
        }
    }
}


/*
 * Discard all data with a uso gte mark
 */
void TupleStreamWrapper::rollbackTo(size_t mark)
{
    if (mark > m_uso) {
        throwFatalException("Truncating the future.");
    }

    // back up the universal stream counter
    m_uso = mark;

    // working from newest to oldest block, throw
    // away blocks that are fully after mark; truncate
    // the block that contains mark.
    if (!(m_currBlock->uso() >= mark)) {
        m_currBlock->truncateTo(mark);
    }
    else {
        StreamBlock *sb = NULL;
        discardBlock(m_currBlock);
        m_currBlock = NULL;
        while (m_pendingBlocks.empty() != true) {
            sb = m_pendingBlocks.back();
            m_pendingBlocks.pop_back();
            if (sb->uso() >= mark) {
                discardBlock(sb);
            }
            else {
                sb->truncateTo(mark);
                m_currBlock = sb;
                break;
            }
        }
    }
}

/*
 * Correctly release and delete a managed buffer that won't
 * be handed off
 */
void TupleStreamWrapper::discardBlock(StreamBlock *sb) {
    delete [] sb->rawPtr();
    delete sb;
}

/*
 * Allocate another buffer, preserving the current buffer's content in
 * the pending queue.
 */
void TupleStreamWrapper::extendBufferChain(size_t minLength)
{
    if (m_defaultCapacity < minLength) {
        // exportxxx: rollback instead?
        throwFatalException("Default capacity is less than required buffer size.");
    }

    if (m_currBlock) {
        m_pendingBlocks.push_back(m_currBlock);
        m_currBlock = NULL;
    }

    char *buffer = new char[m_defaultCapacity];
    if (!buffer) {
        throwFatalException("Failed to claim managed buffer for Export.");
    }

    m_currBlock = new StreamBlock(buffer, m_defaultCapacity, m_uso);
    m_currBlock->setGenerationId(m_generation);
    m_currBlock->setSignature(m_signature);
}

/*
 * Create a new buffer and flush all pending committed data.
 * Creating a new buffer will push all queued data into the
 * pending list for commit to operate against.
 */
void
TupleStreamWrapper::periodicFlush(int64_t timeInMillis,
                                  int64_t lastCommittedTxnId,
                                  int64_t currentTxnId)
{
    // negative timeInMillis instructs a mandatory flush
    if (timeInMillis < 0 || (timeInMillis - m_lastFlush > MAX_BUFFER_AGE)) {
        if (timeInMillis > 0) {
            m_lastFlush = timeInMillis;
        }

        // ENG-866
        //
        // Due to tryToSneakInASinglePartitionProcedure (and probable
        // speculative execution in the future), the EE is not
        // guaranteed to see all transactions in transaction ID order.
        // periodicFlush is handed whatever the most recent txnId
        // executed is, whether or not that txnId is relevant to this
        // export stream.  commit() is enforcing the invariants that
        // the TupleStreamWrapper needs to see for relevant
        // transaction IDs; we choose whichever of currentTxnId or
        // m_openTransactionId here will allow commit() to continue
        // operating correctly.
        int64_t txnId = currentTxnId;
        if (m_openTransactionId > currentTxnId)
        {
            txnId = m_openTransactionId;
        }

        extendBufferChain(0);
        commit(lastCommittedTxnId, txnId, timeInMillis < 0 ? true : false);
        drainPendingBlocks();
    }
}

/*
 * If txnId represents a new transaction, commit previous data.
 * Always serialize the supplied tuple in to the stream.
 * Return m_uso before this invocation - this marks the point
 * in the stream the caller can rollback to if this append
 * should be rolled back.
 */
size_t TupleStreamWrapper::appendTuple(int64_t lastCommittedTxnId,
                                       int64_t txnId,
                                       int64_t seqNo,
                                       int64_t timestamp,
                                       int64_t generationId,
                                       TableTuple &tuple,
                                       TupleStreamWrapper::Type type)
{
    size_t rowHeaderSz = 0;
    size_t tupleMaxLength = 0;

    // Transaction IDs for transactions applied to this tuple stream
    // should always be moving forward in time.
    if (txnId < m_openTransactionId)
    {
        throwFatalException("Active transactions moving backwards");
    }

    commit(lastCommittedTxnId, txnId);

    // Compute the upper bound on bytes required to serialize tuple.
    // exportxxx: can memoize this calculation.
    tupleMaxLength = computeOffsets(tuple, &rowHeaderSz);
    if (generationId > m_generation)
    {
        // Advance the generation ID and then create a new buffer
        // with it
        m_generation = generationId;
        extendBufferChain(m_defaultCapacity);
    }
    if (!m_currBlock)
    {
        extendBufferChain(m_defaultCapacity);
    }

    // If the current block doesn't have enough capacity to hold the
    // maximum tuple size, then this pushes it onto the pending list
    // and allocates a new block of m_defaultCapacity (the argument is
    // a lie)
    if ((m_currBlock->rawLength() + tupleMaxLength) > m_defaultCapacity) {
        extendBufferChain(tupleMaxLength);
    }

    drainPendingBlocks();

    // if this is the first tuple being appended to this block, set
    // the generation ID appropriately.
    if (m_currBlock->offset() == 0)
    {
        m_currBlock->setGenerationId(m_generation);
        m_currBlock->setSignature(m_signature);
    }

    // initialize the full row header to 0. This also
    // has the effect of setting each column non-null.
    ::memset(m_currBlock->mutableDataPtr(), 0, rowHeaderSz);

    // the nullarray lives in rowheader after the 4 byte header length prefix
    uint8_t *nullArray =
      reinterpret_cast<uint8_t*>(m_currBlock->mutableDataPtr() + sizeof (int32_t));

    // position the serializer after the full rowheader
    ExportSerializeOutput io(m_currBlock->mutableDataPtr() + rowHeaderSz,
                             m_currBlock->remaining() - rowHeaderSz);

    // write metadata columns
    io.writeLong(txnId);
    io.writeLong(timestamp);
    io.writeLong(seqNo);
    io.writeLong(m_partitionId);
    io.writeLong(m_siteId);

    // use 1 for INSERT EXPORT op, 0 for DELETE EXPORT op
    io.writeLong((type == INSERT) ? 1L : 0L);

    // write the tuple's data
    tuple.serializeToExport(io, METADATA_COL_CNT, nullArray);

    // write the row size in to the row header
    // rowlength does not include the 4 byte row header
    // but does include the null array.
    ExportSerializeOutput hdr(m_currBlock->mutableDataPtr(), 4);
    hdr.writeInt((int32_t)(io.position()) + (int32_t)rowHeaderSz - 4);

    // update m_offset
    m_currBlock->consumed(rowHeaderSz + io.position());

    // update uso.
    const size_t startingUso = m_uso;
    m_uso += (rowHeaderSz + io.position());
    return startingUso;
}

size_t
TupleStreamWrapper::computeOffsets(TableTuple &tuple,
                                   size_t *rowHeaderSz)
{
    // round-up columncount to next multiple of 8 and divide by 8
    int columnCount = tuple.sizeInValues() + METADATA_COL_CNT;
    int nullMaskLength = ((columnCount + 7) & -8) >> 3;

    // row header is 32-bit length of row plus null mask
    *rowHeaderSz = sizeof (int32_t) + nullMaskLength;

    // metadata column width: 5 int64_ts plus CHAR(1).
    size_t metadataSz = (sizeof (int64_t) * 5) + 1;

    // returns 0 if corrupt tuple detected
    size_t dataSz = tuple.maxExportSerializationSize();
    if (dataSz == 0) {
        throwFatalException("Invalid tuple passed to computeTupleMaxLength. Crashing System.");
    }

    return *rowHeaderSz + metadataSz + dataSz;
}

void
TupleStreamWrapper::pushExportBlock(StreamBlock* sb)
{
    // Push the real block if it contains data.
    if (sb->offset() > 0)
    {
        cout << endl << "Pushing block, generation: " << sb->generationId() << ", uso: " << sb->uso() << ", offset: " << sb->offset() << ", EOS: " << sb->endOfStream() << endl;
        //The block is handed off to the topend which is
        //responsible for releasing the memory associated with the
        //block data. The metadata is deleted here.
        ExecutorContext::getExecutorContext()->getTopend()->
            pushExportBuffer(sb->generationId(),
                             m_partitionId,
                             sb->signature(),
                             sb,
                             false,
                             sb->endOfStream());
        delete sb;
    }
    // Otherwise, don't bother unless we're trying to send end-of-stream
    else if (sb->endOfStream())
    {
        cout << endl << "Pushing block, generation: " << sb->generationId() << ", uso: " << sb->uso() << ", offset: " << sb->offset() << ", EOS: " << sb->endOfStream() << endl;
        ExecutorContext::getExecutorContext()->getTopend()->
            pushExportBuffer(sb->generationId(),
                             m_partitionId,
                             sb->signature(),
                             NULL,
                             false,
                             sb->endOfStream());
        discardBlock(sb);
    }
}
