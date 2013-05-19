/* Copyright (C) 2013 by H-Store Project
 * Brown University
 * Massachusetts Institute of Technology
 * Yale University
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

#include "migration/MigrationManager.h"
#include "common/types.h"
#include "common/debuglog.h"
#include "storage/table.h"
#include "storage/persistenttable.h"
#include "storage/tablefactory.h"

#include <string>
#include <vector>

namespace voltdb
{
            
// -----------------------------------------
// MigrationManager Implementation 
// -----------------------------------------
    
MigrationManager::MigrationManager(ExecutorContext *executorContext, catalog::Database *catalogDatabase) :
    m_executorContext(executorContext),
    m_catalogDatabase(catalogDatabase) {
    
    // TODO: Precompute all of the stuff that we need for each table
    
    m_extractedTables.clear();
    m_extractedTableNames.clear();
}

MigrationManager::~MigrationManager() {
    // TODO
}


Table* MigrationManager::extractRange(PersistentTable *table,  const NValue minKey, const NValue maxKey, int32_t requestToken) {
    VOLT_DEBUG("ExtractRange %s %s - %s ", table->name().c_str(),minKey.debug().c_str(),maxKey.debug().c_str() );        
    //Get the right index to use
    //TODO andy ae this should be cached on initialization. do tables exists? when should migration mgr be created? should it exist in dbcontext
    
    TableIndex* partitionIndex = getPartitionColumnIndex(table);    
    int partitionColumn = table->partitionColumn();
    bool partitionColumnIsIndexed=true;
    if(partitionIndex == NULL){
        //TODO ae what do we do when we have no index for the partition colum?
        partitionColumnIsIndexed = false;
    }       
    
    TableTuple tuple(table->schema());
    //TODO ae andy -> How many byes should we set this to? Below is just a silly guess
    int outTableSizeInBytes = 1024; 
    //int outTableSizeInBytes = (maxKey.op_subtract(minKey)).castAs(VALUE_TYPE_INTEGER).getInteger() *tuple.maxExportSerializationSize();
    
    //output table
    Table* outputTable = reinterpret_cast<Table*>(TableFactory::getCopiedTempTable(table->databaseId(),
            table->name(),table,&outTableSizeInBytes));
    
    
    std::vector<ValueType> keyColumnTypes(1, minKey.getValueType());
    std::vector<int32_t> keyColumnLengths(1, NValue::getTupleStorageSize(minKey.getValueType()));
    std::vector<bool> keyColumnAllowNull(1, true);
    TupleSchema* keySchema = TupleSchema::createTupleSchema(keyColumnTypes,keyColumnLengths,keyColumnAllowNull,true);
    TableTuple searchkey(keySchema);
    searchkey.move(new char[searchkey.tupleLength()]);
    searchkey.setNValue(0, minKey);
    
    //Do we have a single key to pull
    if(minKey.compare(maxKey)==0 && partitionColumnIsIndexed){
        bool found = partitionIndex->moveToKey(&searchkey);    
        if(found){
            VOLT_DEBUG("Found");
            if(!(tuple = partitionIndex->nextValueAtKey()).isNullTuple()){
                //TODO check if not migrated                
                if (!outputTable->insertTuple(tuple))
                {
                    VOLT_ERROR("Failed to insert tuple from table '%s' into"
                            " output table '%s'",
                            table->name().c_str(),
                            outputTable->name().c_str());
                    return NULL;
                }                
                //TODO set as migrated and delete later
                table->deleteTuple(tuple,true);
            } else{
                VOLT_ERROR("Tuple not found but index indicated it exists");
                return NULL;
            }            
        }
        else{
            VOLT_DEBUG("key not found for single key extract");       
            return NULL;
        }
    } else if(minKey.compare(maxKey)<0){
        //TODO ae andy -> on searching and checking for the max key condition
            // (cont) should we be using an expression or ok to just do  value check end value on iteration?
        //IF b-Tree
        if (partitionColumnIsIndexed && partitionIndex->getScheme().type == BALANCED_TREE_INDEX){            
            //We have a range to check
            partitionIndex->moveToKeyOrGreater(&searchkey);    
            while(((!(tuple = partitionIndex->nextValueAtKey()).isNullTuple()) ||
            (!(tuple = partitionIndex->nextValue()).isNullTuple())) && (maxKey.compare(tuple.getNValue(partitionColumn)) >0)){                
                //TODO ae check if ! migrated
                if (!outputTable->insertTuple(tuple))
                {
                    VOLT_ERROR("Failed to insert tuple from table '%s' into  output table '%s'",table->name().c_str(),outputTable->name().c_str());
                    return NULL;
                }
                //TODO set as migrated and delete later
                table->deleteTuple(tuple,true);
            }
        }  // Else if hash index
        else if (!partitionColumnIsIndexed || partitionIndex->getScheme().type == HASH_TABLE_INDEX){
            //find key
            
            //TODO ae andy -> assume we cannot leverage anything about hashing with ranges, correct?
            //Iterate through results
            TableIterator iterator(table);           
            while (iterator.next(tuple))
            {
                //Is the partitionColumn in the range between min inclusive and max exclusive
                if (minKey.compare(tuple.getNValue(partitionColumn)) <= 0 && maxKey.compare(tuple.getNValue(partitionColumn)) >0){
                    //TODO ae check if ! migrated
                    if (!outputTable->insertTuple(tuple))
                    {
                        VOLT_ERROR("Failed to insert tuple from table '%s' into  output table '%s'",table->name().c_str(),outputTable->name().c_str());
                        return NULL;
                    }
                    //TODO set as migrated and delete later
                    table->deleteTuple(tuple,true);
                }
            }
        } else {            
            throwFatalException("Unsupported Index type %d",partitionIndex->getScheme().type );
        }     
    } else {
        //Min key should never be greater than maxKey        
        //TODO ae andy -> Appropriate exception to throw?
        throwFatalException("Max key is smaller than min key");
    } 
    VOLT_DEBUG("Output Table %s",outputTable->debug().c_str());
    m_extractedTables[requestToken] = outputTable;
    m_extractedTableNames[requestToken] = table->name();
    return outputTable;
}

TableIndex* MigrationManager::getPartitionColumnIndex(PersistentTable *table) {
    int partitionColumn = table->partitionColumn();
    std::vector<TableIndex*> tableIndexes = table->allIndexes();
    
    for (int i = 0; i < table->indexCount(); ++i) {
        TableIndex *index = tableIndexes[i];
        
        VOLT_DEBUG("Index %s ", index->debug().c_str());
        //One column in this index
        if(index->getColumnCount() == 1 && index->isUniqueIndex()) {
            if (index->getColumnIndices()[0] == partitionColumn){
                VOLT_DEBUG("Index matches");
                return index;
            }
        }
    }
    return NULL;
}


bool MigrationManager::confirmExtractDelete(int32_t requestTokenId) {
    if(m_extractedTables.find(requestTokenId) == m_extractedTables.end()){
        VOLT_DEBUG("confirmExtractDelte requestTokenId was not found");
        return false;
    }
    else {
        VOLT_DEBUG("confirmExtractDelete for token %d", requestTokenId);
        Table* migratedData = m_extractedTables[requestTokenId];
        migratedData->deleteAllTuples(true);
        delete migratedData;        
        m_extractedTables.erase(requestTokenId);
        m_extractedTableNames.erase(requestTokenId);
        return true;
    }      
    return false;
}
bool MigrationManager::undoExtractDelete(int32_t requestTokenId) {
    throwFatalException("Undo delete not implemented yet");
    return false;
}


}
