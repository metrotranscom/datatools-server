package com.conveyal.datatools.editor.datastore;

import com.conveyal.gtfs.model.Calendar;
import com.conveyal.datatools.editor.models.transit.Route;
import com.conveyal.datatools.editor.models.transit.ScheduleException;
import com.conveyal.datatools.editor.models.transit.Stop;
import com.conveyal.datatools.editor.models.transit.Trip;
import com.conveyal.datatools.editor.models.transit.TripPattern;
import com.conveyal.datatools.editor.models.transit.TripPatternStop;
import org.mapdb.BTreeMap;
import org.mapdb.DB;
import org.mapdb.Fun.Tuple2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** represents a snapshot database. It's generally not actually a transaction, but rather writing to a transactionless db, for speed */
public class SnapshotTx extends DatabaseTx {
    /** create a snapshot database */
    public static final Logger LOG = LoggerFactory.getLogger(SnapshotTx.class);
    public SnapshotTx(DB tx) {
        super(tx);
    }

    /** make the snapshot */
    public void make (AgencyTx master) {
        // make sure it's empty
        if (tx.getAll().size() != 0)
            throw new IllegalStateException("Cannot snapshot into non-empty db");

        int rcount = pump("routes", (BTreeMap) master.routes);
        LOG.info("Snapshotted %s routes", rcount);
        int ccount = pump("calendars", (BTreeMap) master.calendars);
        LOG.info("Snapshotted %s calendars", ccount);
        int ecount = pump("exceptions", (BTreeMap) master.exceptions);
        LOG.info("Snapshotted %s schedule exceptions", ecount);
        int tpcount = pump("tripPatterns", (BTreeMap) master.tripPatterns);
        LOG.info("Snapshotted %s patterns", tpcount);
        int tcount = pump("trips", (BTreeMap) master.trips);
        LOG.info("Snapshotted %s trips", tcount);
        int scount = pump("stops", (BTreeMap) master.stops);
        LOG.info("Snapshotted %s stops", scount);

        // while we don't snapshot indices, we do need to snapshot histograms as they aren't restored
        // (mapdb ticket 453)
        pump("tripCountByCalendar", (BTreeMap) master.tripCountByCalendar);
        pump("scheduleExceptionCountByDate", (BTreeMap) master.scheduleExceptionCountByDate);
        pump("tripCountByPatternAndCalendar", (BTreeMap) master.tripCountByPatternAndCalendar);

        this.commit();
        LOG.info("Snapshot finished");
    }

    /**
     * restore into an agency. this will OVERWRITE ALL DATA IN THE AGENCY's MASTER BRANCH, with the exception of stops
     * @return any stop IDs that had been deleted and were restored so that this snapshot would be valid.
     */
    public List<Stop> restore (String agencyId) {
        DB targetTx = VersionedDataStore.getRawAgencyTx(agencyId);

        for (String obj : targetTx.getAll().keySet()) {
            if (obj.equals("snapshotVersion") || obj.equals("stops"))
                // except don't overwrite the counter that keeps track of snapshot versions
                // we also don't overwrite the stops completely, as we need to merge them
                continue;
            else
                targetTx.delete(obj);
        }

        int rcount, ccount, ecount, pcount, tcount;

        if (tx.exists("routes"))
            rcount = pump(targetTx, "routes", (BTreeMap) this.<String, Route>getMap("routes"));
        else
            rcount = 0;
        LOG.info("Restored %s routes", rcount);

        if (tx.exists("calendars"))
            ccount = pump(targetTx, "calendars", (BTreeMap) this.<String, Calendar>getMap("calendars"));
        else
            ccount = 0;
        LOG.info("Restored %s calendars", ccount);

        if (tx.exists("exceptions"))
            ecount = pump(targetTx, "exceptions", (BTreeMap) this.<String, ScheduleException>getMap("exceptions"));
        else
            ecount = 0;
        LOG.info("Restored %s schedule exceptions", ecount);

        if (tx.exists("tripPatterns"))
            pcount = pump(targetTx, "tripPatterns", (BTreeMap) this.<String, TripPattern>getMap("tripPatterns"));
        else
            pcount = 0;
        LOG.info("Restored %s patterns", pcount);

        if (tx.exists("trips"))
            tcount = pump(targetTx, "trips", (BTreeMap) this.<String, Trip>getMap("trips"));
        else
            tcount = 0;
        LOG.info("Restored %s trips", tcount);

        // restore histograms, see jankotek/mapdb#453
        if (tx.exists("tripCountByCalendar"))
            pump(targetTx, "tripCountByCalendar", (BTreeMap) this.<String, Long>getMap("tripCountByCalendar"));

        if (tx.exists("tripCountByPatternAndCalendar"))
            pump(targetTx, "tripCountByPatternAndCalendar",
                    (BTreeMap) this.<Tuple2<String, String>, Long>getMap("tripCountByPatternAndCalendar"));

        // make an agencytx to build indices and restore stops
        LOG.info("Rebuilding indices, this could take a little while . . . ");
        AgencyTx atx = new AgencyTx(targetTx);
        LOG.info("done.");

        LOG.info("Restoring deleted stops");

        // restore any stops that have been deleted
        List<Stop> restoredStops = new ArrayList<Stop>();
        if (tx.exists("stops")) {
            BTreeMap<String, Stop> oldStops = this.<String, Stop>getMap("stops");

            for (TripPattern tp : atx.tripPatterns.values()) {
                for (TripPatternStop ps : tp.patternStops) {
                    if (!atx.stops.containsKey(ps.stopId)) {
                        Stop stop = oldStops.get(ps.stopId);
                        atx.stops.put(ps.stopId, stop);
                        restoredStops.add(stop);
                    }
                }
            }
        }
        LOG.info("Restored %s deleted stops", restoredStops.size());

        atx.commit();

        return restoredStops;
    }

    /** close the underlying data store */
    public void close () {
        tx.close();
        closed = true;
    }
}