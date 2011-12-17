/*
 * Copyright (C) 2011 University of Washington
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.opendatakit.aggregate.util;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.opendatakit.aggregate.constants.BeanDefs;
import org.opendatakit.aggregate.task.Watchdog;
import org.opendatakit.common.persistence.CommonFieldsBase;
import org.opendatakit.common.persistence.DataField;
import org.opendatakit.common.persistence.Datastore;
import org.opendatakit.common.persistence.PersistConsts;
import org.opendatakit.common.persistence.exception.ODKDatastoreException;
import org.opendatakit.common.persistence.exception.ODKEntityNotFoundException;
import org.opendatakit.common.security.User;
import org.opendatakit.common.web.CallingContext;

/**
 * Implements the mechanisms used in GAE to launch Watchdog during periods of
 * website activity ( triggerWatchdog ) and from within the Watchdog
 * implementation when there are background tasks requiring supervision
 * ( scheduleFutureWatchdog ).
 * 
 * To do that, it tracks:
 * - the start time of each Watchdog iteration ( updateWatchdogStart ),
 * - the time-at-enqueuing of a Watchdog Task on the AppEngine queue,
 * - the time-to-fire of the earliest future Watchdog activity.
 * 
 * @author mitchellsundt@gmail.com
 * 
 */
public class BackendActionsTable extends CommonFieldsBase {

  private static final String WATCHDOG_SCHEDULING_ROW_ID = "rid:watchdog_scheduling";
  private static final String WATCHDOG_ENQUEUE_ROW_ID = "rid:watchdog_enqueue";
  private static final String WATCHDOG_START_ROW_ID = "rid:watchdog_start";

  private static final String TABLE_NAME = "_backend_actions";

  private static final DataField LAST_REVISION_DATE = new DataField("LAST_REVISION",
      DataField.DataType.DATETIME, true);

  private static final Log logger = LogFactory.getLog(BackendActionsTable.class);

  // fields used to determine triggering of UploadSubmissions task creation.
  
  public static long lastHashmapCleanTimestamp = 0L; // time of last clear of hashmap
  public static long HASHMAP_LIFETIME_MILLISECONDS = PersistConsts.MAX_SETTLE_MILLISECONDS * 10L;
  public static Map<String, Long> lastPublisherRevision = new HashMap<String, Long>();
  public static long PUBLISHING_DELAY_MILLISECONDS = 500L + PersistConsts.MAX_SETTLE_MILLISECONDS;
  // field used to record when watchdogs actually start
  private static long lastWatchdogStartTime = 0L;
  
  // fields used to determine when to fire a watchdog based upon usage.
  
  private static long lastFetchTime = 0L; // time of last (complete) fetch of these values.
  private static long lastWatchdogEnqueueTime = 0L;
  private static long lastWatchdogSchedulingTime = 0L;

  /**
   * Construct a relation prototype. Only called via
   * {@link #assertRelation(Datastore, User)}
   * 
   * @param schemaName
   */
  protected BackendActionsTable(String schemaName) {
    super(schemaName, TABLE_NAME);
    fieldList.add(LAST_REVISION_DATE);
  }

  /**
   * Construct an empty entity. Only called via {@link #getEmptyRow(User)}
   * 
   * @param ref
   * @param user
   */
  protected BackendActionsTable(BackendActionsTable ref, User user) {
    super(ref, user);
  }

  // Only called from within the persistence layer.
  @Override
  public CommonFieldsBase getEmptyRow(User user) {
    BackendActionsTable t = new BackendActionsTable(this, user);
    return t;
  }

  private Date getLastRevisionDate() {
    return getDateField(LAST_REVISION_DATE);
  }

  private void setLastRevisionDate(Date value) {
    setDateField(LAST_REVISION_DATE, value);
  }

  private static BackendActionsTable relation = null;

  /**
   * This is private because this table implements a singleton pattern.
   * 
   * @param datastore
   * @param user
   * @return
   * @throws ODKDatastoreException
   */
  private static BackendActionsTable assertRelation(Datastore datastore, User user)
      throws ODKDatastoreException {
    if (relation == null) {
      BackendActionsTable relationPrototype;
      relationPrototype = new BackendActionsTable(datastore.getDefaultSchemaName());
      datastore.assertRelation(relationPrototype, user);
      relation = relationPrototype;
    }
    return relation;
  }

  /**
   * This retrieves the singleton record.
   * 
   * @param uri
   * @param datastore
   * @param user
   * @return
   * @throws ODKDatastoreException
   */
  private static final BackendActionsTable getSingletonRecord(String uri, Datastore datastore,
      User user) throws ODKDatastoreException {
    BackendActionsTable prototype = assertRelation(datastore, user);
    BackendActionsTable record = null;
    try {
      record = datastore.getEntity(prototype, uri, user);
    } catch (ODKEntityNotFoundException e) {
      record = datastore.createEntityUsingRelation(prototype, user);
      record.setStringField(prototype.primaryKey, uri);
      record.setLastRevisionDate(new Date(0)); // NOTE: Defaults differently
                                               // than SecurityRevisionsTable
      datastore.putEntity(record, user);
    }
    return record;
  }
  
  public static final synchronized boolean triggerPublisher(String uriFsc, CallingContext cc) {
    boolean wasDaemon = cc.getAsDeamon();
    cc.setAsDaemon(true);
    try {
      Datastore ds = cc.getDatastore();
      User user = cc.getCurrentUser();
      long now = System.currentTimeMillis();
      if ( lastHashmapCleanTimestamp + HASHMAP_LIFETIME_MILLISECONDS < now ) {
        lastPublisherRevision.clear();
        lastHashmapCleanTimestamp = now;
      }

      BackendActionsTable t = null;
      Long oldTime = lastPublisherRevision.get(uriFsc);
      if ( oldTime == null ) {
        // see if we have anything in the table (created if missing).
        t = BackendActionsTable.getSingletonRecord(uriFsc, ds, user);
        oldTime = t.getLastRevisionDate().getTime();
        lastPublisherRevision.put(uriFsc, oldTime);
      }
      
      boolean publish = false;
      if ( oldTime + PUBLISHING_DELAY_MILLISECONDS < now ) {
        // fetch actual record if not yet fetched
        if ( t == null ) {
          t = BackendActionsTable.getSingletonRecord(uriFsc, ds, user);
          oldTime = t.getLastRevisionDate().getTime();
          lastPublisherRevision.put(uriFsc, oldTime);
        }
        
        // and double-check that we still meet the condition...
        if ( oldTime + PUBLISHING_DELAY_MILLISECONDS < now ) {
          t.setLastRevisionDate(new Date(now));
          ds.putEntity(t, user);
          lastPublisherRevision.put(uriFsc, now);
          publish = true;
        }
      }
      return publish;
    } catch (ODKDatastoreException e) {
      e.printStackTrace();
      return false;
    } finally {
      cc.setAsDaemon(wasDaemon);
    }
  }
  
  /**
   * Updates the time the watchdog last ran.  
   * Called only from within the WatchdogWorkerImpl class.
   * 
   * @param cc
   */
  public static final synchronized void updateWatchdogStart(CallingContext cc) {
    boolean wasDaemon = cc.getAsDeamon();
    
    try {
      cc.setAsDaemon(true);
      Datastore ds = cc.getDatastore();
      User user = cc.getCurrentUser();

      // fetch the scheduling row
      BackendActionsTable t = getSingletonRecord(WATCHDOG_START_ROW_ID, ds, user);
      long oldStartTime = t.getLastRevisionDate().getTime();
      lastWatchdogStartTime = System.currentTimeMillis();
      t.setLastRevisionDate(new Date(lastWatchdogStartTime));
      ds.putEntity(t, user);

      long expectedNextStart = 
          oldStartTime + Watchdog.WATCHDOG_BUSY_RETRY_INTERVAL_MILLISECONDS;
      
      if ( expectedNextStart > lastWatchdogStartTime ) {
        logger.warn("watchdog started early: " +
            Long.toString(lastWatchdogStartTime) + 
            " vs " +  Long.toString(expectedNextStart) );
      }
      
    } catch (ODKDatastoreException e) {
      e.printStackTrace();
    } finally {
      cc.setAsDaemon(wasDaemon);
    }
  }
  
  private static final void logValues( String tag, long now, long futureMilliseconds, long requestedTime ) {
    String msg;
    if ( requestedTime == -1L ) {
      msg = String.format("%7$s last Fetch: %1$8d [S: %2$8d Eq: %3$8d Fs: %4$8d] futureMillis: %5$8d",
          (lastFetchTime-now), 
          (lastWatchdogStartTime-now), 
          (lastWatchdogEnqueueTime-now), 
          (lastWatchdogSchedulingTime-now),
          futureMilliseconds, (requestedTime-now), tag );
    } else {
      msg = String.format("%7$s last Fetch: %1$8d [S: %2$8d Eq: %3$8d Fs: %4$8d] futureMillis: %5$8d requested: %6$8d",
          (lastFetchTime-now), 
          (lastWatchdogStartTime-now), 
          (lastWatchdogEnqueueTime-now), 
          (lastWatchdogSchedulingTime-now),
          futureMilliseconds, (requestedTime-now), tag );
    }
    logger.info(msg);
  }

  private static final String INCOMING  = "incoming-";
  private static final String FETCHED   = "-fetched-";
  private static final String SCHEDULED = "Fs-update";
  private static final String CLEARED   = "Fs-clear-";
  private static final String ENQUEUED  = "Eq-update";

  private static class WatchdogRecords {
    private BackendActionsTable startTime = null;
    private BackendActionsTable enqueueTime = null;
    private BackendActionsTable schedulingTime = null;

    WatchdogRecords( long now, CallingContext cc ) throws ODKDatastoreException {
      
      // refetch all the data if it is more than the settle period old...
      if ( lastFetchTime + PersistConsts.MAX_SETTLE_MILLISECONDS < now ) {
        fetchAll( now, cc);
      }
    }
    
    void fetchAll( long now, CallingContext cc ) throws ODKDatastoreException {
      Datastore ds = cc.getDatastore();
      User user = cc.getCurrentUser();

      if ( lastFetchTime == now ) return;
      
      // this is gratuitous, but it puts the queue activities in context
      // it would only be updated on the background process if we didn't
      // read it here.
      startTime = getSingletonRecord(WATCHDOG_START_ROW_ID, ds, user);
      lastWatchdogStartTime = startTime.getLastRevisionDate().getTime();
      
      enqueueTime = getSingletonRecord(WATCHDOG_ENQUEUE_ROW_ID, ds, user);
      lastWatchdogEnqueueTime = enqueueTime.getLastRevisionDate().getTime();
      
      schedulingTime = getSingletonRecord(WATCHDOG_SCHEDULING_ROW_ID, ds, user);
      lastWatchdogSchedulingTime = schedulingTime.getLastRevisionDate().getTime();

      lastFetchTime = now;

      logValues( FETCHED, now, -1, -1 );
    }
    
  }
  
  /**
   * This is effectively GAE-specific: Tomcat installations use a 
   * scheduled executor to periodically fire the watchdog (and do 
   * not use this mechanism).
   * 
   * Schedule a watchdog to run the specified number of milliseconds
   * into the future (zero is OK).
   * 
   * Schedule to ensure that Watchdogs are run at with at least 
   * WATCHDOG_BUSY_RETRY_INTERVAL_MILLISECONDS between invocations.
   * 
   * @param futureMilliseconds
   * @param cc
   */
  public static final synchronized void scheduleFutureWatchdog(long futureMilliseconds,
      CallingContext cc) {
    boolean wasDaemon = cc.getAsDeamon();

    long now = System.currentTimeMillis();
    logValues( INCOMING, now, futureMilliseconds, -1L );
    
    try {
      cc.setAsDaemon(true);
      Datastore ds = cc.getDatastore();
      User user = cc.getCurrentUser();

      WatchdogRecords records = new WatchdogRecords(now, cc);
      
      // don't schedule any timer before the blackoutTime
      long blackoutTime = Math.max(lastWatchdogEnqueueTime +
          Watchdog.WATCHDOG_BUSY_RETRY_INTERVAL_MILLISECONDS, now);

      // Revise the request to start at the end of the blackout period.
      // Two cases: (1) immediate request (2) future request
      long requestedWatchdogSchedulingTime = Math.max(blackoutTime, now + futureMilliseconds);

      // Update the BackendActionsTable records and/or fire a Watchdog if:
      // (1) there is an active timer that is in the past
      // or
      // (2) the adjusted request time is now
      // or 
      // (3) there is no active scheduling time (and this request is in the future)
      //    (the scheduling time is at or before the enqueue time) 
      // or 
      // (4) the active scheduling time should be lowered due to this request.
      if ( (lastWatchdogSchedulingTime < now &&
            lastWatchdogSchedulingTime > lastWatchdogEnqueueTime) ||
           (requestedWatchdogSchedulingTime == now) ||
           (lastWatchdogSchedulingTime <= lastWatchdogEnqueueTime) ||
           (requestedWatchdogSchedulingTime < lastWatchdogSchedulingTime) ) {
        
        // refetch and update our values...
        records.fetchAll(now, cc);

        // and recompute things...
        blackoutTime = Math.max(lastWatchdogEnqueueTime +
            Watchdog.WATCHDOG_BUSY_RETRY_INTERVAL_MILLISECONDS, now);
        
        requestedWatchdogSchedulingTime = Math.max(blackoutTime, now + futureMilliseconds);

        // OK -- see if the conditions for update still apply...
        boolean activeSchedulingTimeInThePast = 
            (lastWatchdogSchedulingTime < now &&
                lastWatchdogSchedulingTime > lastWatchdogEnqueueTime);
        
        if ( activeSchedulingTimeInThePast ||
             (requestedWatchdogSchedulingTime == now) ||
             (lastWatchdogSchedulingTime <= lastWatchdogEnqueueTime) ||
             (requestedWatchdogSchedulingTime < lastWatchdogSchedulingTime) ) {
          // YES... update everything
          
          // Case (1) and/or (2)
          // enqueue any request first...
          if ( activeSchedulingTimeInThePast ||
               requestedWatchdogSchedulingTime == now ) {
            // fire the Watchdog...
            Watchdog dog = (Watchdog) cc.getBean(BeanDefs.WATCHDOG);
            dog.onUsage(0, cc);
  
            // update enqueue value...
            records.enqueueTime.setLastRevisionDate(new Date(now));
            lastWatchdogEnqueueTime = now;
            ds.putEntity(records.enqueueTime, user);
            logValues( ENQUEUED, now, futureMilliseconds, requestedWatchdogSchedulingTime );
          }

          // If we fired a stale request (case (1)) then don't schedule anything.
          // The Watchdog itself will handle queuing its next runtime. 
          if ( !activeSchedulingTimeInThePast ) {
            // EXCLUDING Case (1)...
            
            // if we fired an immediate request, we should
            // reset the scheduling time to the past (retroactively)
            // to cancel any future request.
            if (requestedWatchdogSchedulingTime == now) {
              // Case (2)...
              if (lastWatchdogSchedulingTime > lastWatchdogEnqueueTime) {
                // there's a future timer that should be cleared
                records.schedulingTime.setLastRevisionDate(new Date(now));
                lastWatchdogSchedulingTime = now;
                ds.putEntity(records.schedulingTime, user);
                logValues( CLEARED, now, futureMilliseconds, requestedWatchdogSchedulingTime );
              }
            } else {
              // Case (3) or (4) - schedule a future event
              records.schedulingTime.setLastRevisionDate(new Date(requestedWatchdogSchedulingTime));
              lastWatchdogSchedulingTime = requestedWatchdogSchedulingTime;
              ds.putEntity(records.schedulingTime, user);
              logValues( SCHEDULED, now, futureMilliseconds, requestedWatchdogSchedulingTime );
            }
          }
        }
      }
    } catch (ODKDatastoreException e) {
      e.printStackTrace();
    } finally {
      cc.setAsDaemon(wasDaemon);
    }
  }

  /**
   * This is effectively GAE-specific: Tomcat installations use a 
   * scheduled executor to periodically fire the watchdog (and do 
   * not use this mechanism).
   * 
   * Check whether a watchdog should be spun up (immediately). Spin one up
   * every Watchdog.WATCHDOG_IDLING_RETRY_INTERVAL_MILLISECONDS. Note that if the
   * watchdog determines that there is work pending, it will schedule itself.
   * 
   * @param cc
   */
  public static final synchronized void triggerWatchdog(CallingContext cc) {
    // don't schedule any timer before the next idling retry time
    long nextIdlingRetryTime = lastWatchdogEnqueueTime +
                        Watchdog.WATCHDOG_IDLING_RETRY_INTERVAL_MILLISECONDS;

    long futureMilliseconds = Math.max(0L,
        nextIdlingRetryTime - System.currentTimeMillis());
    
    scheduleFutureWatchdog(futureMilliseconds, cc);
  }
}