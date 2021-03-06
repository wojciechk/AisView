/* Copyright (c) 2011 Danish Maritime Authority.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dk.dma.ais.view.handler;

import dk.dma.ais.data.AisClassATarget;
import dk.dma.ais.data.AisTarget;
import dk.dma.ais.data.AisVesselPosition;
import dk.dma.ais.data.AisVesselTarget;
import dk.dma.ais.data.IPastTrack;
import dk.dma.ais.data.PastTrackPoint;
import dk.dma.ais.data.PastTrackSortedSet;
import dk.dma.ais.message.AisMessage;
import dk.dma.ais.message.IVesselPositionMessage;
import dk.dma.ais.packet.AisPacket;
import dk.dma.ais.store.AisStoreQueryBuilder;
import dk.dma.ais.store.AisStoreQueryResult;
import dk.dma.ais.tracker.targetTracker.TargetInfo;
import dk.dma.ais.view.common.grid.Grid;
import dk.dma.ais.view.common.grid.GridFactory;
import dk.dma.ais.view.common.web.QueryParams;
import dk.dma.ais.view.configuration.AisViewConfiguration;
import dk.dma.ais.view.rest.VesselListFilter;
import dk.dma.ais.view.rest.json.VesselCluster;
import dk.dma.ais.view.rest.json.VesselClusterJsonRepsonse;
import dk.dma.ais.view.rest.json.VesselList;
import dk.dma.db.cassandra.CassandraConnection;
import dk.dma.enav.model.Country;
import dk.dma.enav.model.geometry.BoundingBox;
import dk.dma.enav.model.geometry.CoordinateSystem;
import dk.dma.enav.model.geometry.Position;
import org.apache.log4j.Logger;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Handler for received AisPackets
 */
public class AisViewHelper {

    @SuppressWarnings("unused")
    private static Logger LOG = Logger.getLogger(AisViewHelper.class);
    private final AisViewConfiguration conf;

    public AisViewHelper(AisViewConfiguration conf) {
        this.conf = conf;
    }

    /**
     * Returns a casted AisVesselTarget of the given AisTarget if it is an
     * instance of AisVesselTarget.
     * 
     * @param target
     * @param filter
     * @return
     */
    public AisVesselTarget getFilteredAisVessel(AisTarget target,
            VesselListFilter filter) {

        if (!(target instanceof AisVesselTarget)) {
            return null;
        }
        AisVesselTarget vesselTarget = (AisVesselTarget) target;

        Map<String, HashSet<String>> filterMap = filter.getFilterMap();

        // Maybe filtered away
        Set<String> vesselClass = filterMap.get("vesselClass");
        if (vesselClass != null) {
            String vc = (target instanceof AisClassATarget) ? "A" : "B";
            if (!vesselClass.contains(vc)) {
                return null;
            }
        }
        Set<String> country = filterMap.get("country");
        if (country != null) {
            Country mc = target.getCountry();
            if (mc == null) {
                return null;
            }
            if (!country.contains(mc.getThreeLetter())) {
                return null;
            }
        }

        Set<String> staticReport = filterMap.get("staticReport");
        if (staticReport != null) {
            boolean hasStatic = vesselTarget.getVesselStatic() != null;
            if (staticReport.contains("yes") && !hasStatic) {
                return null;
            }
            if (staticReport.contains("no") && hasStatic) {
                return null;
            }
        }

        return vesselTarget;
    }

    /**
     * Generates an IPastTrack from AisStore
     * 
     * @param mmsi
     * @param mostRecent
     * @param timeBack
     * @param tolerance
     * @param minDist
     * @return
     */
    public IPastTrack generatePastTrackFromAisStore(
            int mmsi, long mostRecent, long timeBack, double tolerance,
            int minDist, CassandraConnection con) {

        IPastTrack pt = new PastTrackSortedSet();
        // just one query
        AisStoreQueryBuilder query = AisStoreQueryBuilder.forMmsi(mmsi)
                .setInterval(mostRecent - timeBack, mostRecent);
        AisStoreQueryResult result = con.execute(query);
        Iterator<AisPacket> it = result.iterator();
        
        AisVesselTarget aisTarget = null;
        
        while (it.hasNext() && !result.isCancelled()) {
            AisPacket p = it.next();
            AisMessage m = p.tryGetAisMessage();
            
            if (aisTarget == null) {
                AisTarget tmp = AisTarget.createTarget(m);
                if (tmp instanceof AisVesselTarget) {
                    aisTarget = (AisVesselTarget) tmp;
                }
            } else {
                try {
                    aisTarget.update(m);
                } catch (IllegalArgumentException e) {
                    // Trying To update class ? target with report of other
                    // target type
                } catch (NullPointerException e) {
                    // TODO:
                }
            }

            if (m instanceof IVesselPositionMessage) {
                pt.addPosition(aisTarget.getVesselPosition(), minDist);
            }
        }


        return pt;
    }

    /**
     * Returns false if target is out of specified area. Nothing will be
     * rejected if the area is not specified.
     * 
     * 
     * @param target
     *            The target to test
     * @param pointA
     *            Upper left corner of area.
     * @param pointB
     *            Bottom right corner of area.
     * 
     * 
     * 
     * @return false if target is out of specified area, else true.
     */
    @SuppressWarnings("unused")
    private static boolean rejectedByPosition(AisVesselTarget target,
            Position pointA, Position pointB) {

        // Check if requested area is null
        if (pointA == null || pointB == null) {
            return false;
        }

        // Check if vessel has a position
        if (target.getVesselPosition() == null
                || target.getVesselPosition().getPos() == null) {
            return true;
        }

        // Latitude check - Reject targets not between A and B
        if (target.getVesselPosition().getPos().getLatitude() <= pointA
                .getLatitude()
                && target.getVesselPosition().getPos().getLatitude() >= pointB
                        .getLatitude()) {

            // Longitude check - Accept targets between A and B
            if (pointB.getLongitude() <= pointA.getLongitude()
                    && (target.getVesselPosition().getPos().getLongitude() >= pointA
                            .getLongitude() || target.getVesselPosition()
                            .getPos().getLongitude() <= pointB.getLongitude())) {

                return false;
            }

            // Longitude - Reject targets between B and A - Accept others
            if (pointA.getLongitude() <= pointB.getLongitude()
                    && (target.getVesselPosition().getPos().getLongitude() >= pointB
                            .getLongitude() || target.getVesselPosition()
                            .getPos().getLongitude() <= pointA.getLongitude())) {
                return true;

            } else if (pointA.getLongitude() <= pointB.getLongitude()) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Returns a list of vessel clusters based on a filtering. The returned list
     * does only contain clusters with vessels.
     * 
     * @param size
     * @param limit
     * @return
     */   
    public VesselClusterJsonRepsonse getClusterResponse(
            Stream<TargetInfo> targets, int requestId, int limit, double size, Position pointA,
            Position pointB, Integer inWorld) {
        
        Grid grid = GridFactory.getInstance().getGrid(size);

        // Maps cell ids to vessel clusters
        ConcurrentHashMap<Long, VesselCluster> map = new ConcurrentHashMap<Long, VesselCluster>();
        
        
        // Iterate over targets
        targets.parallel().forEach(new Consumer<TargetInfo>() {

            @Override
            public void accept(TargetInfo target) {

                if (!target.hasPositionInfo()) {
                    return;
                }
                
                if (target.getPosition() == null) {
                    return;
                }
                
                
                Position pos = target.getPosition();
                if (!Position.isValid(pos.getLatitude(), pos.getLongitude())) {
                    return;
                };
                
                long cellId = grid.getCellId(pos.getLatitude(),
                        pos.getLongitude());

                // Only create vessel cluster if new
                if (map.containsKey(cellId)) {

                    map.get(cellId).incrementCount();

                    if (map.get(cellId).getCount() < limit) {
                        map.get(cellId).getVessels()
                                .addTarget(target, target.getMmsi());
                    }

                } else {

                    Position from = grid.getGeoPosOfCellId(cellId);

                    double toLon = from.getLongitude()
                            + grid.getCellSizeInDegrees();
                    double toLat = from.getLatitude()
                            + grid.getCellSizeInDegrees();
                    Position to = Position.create(toLat, toLon);

                    VesselCluster cluster = new VesselCluster(from, to, 1,
                            new VesselList());
                    map.put(cellId, cluster);
                    map.get(cellId).getVessels()
                            .addTarget(target, target.getMmsi());
                    
                }

            }
        });
        
        
        // Calculate density
        map.forEach(new BiConsumer<Long,VesselCluster>() {
            @Override
            public void accept(Long t, VesselCluster c) {
                Position from = Position.create(c.getFrom().getLatitude(), c
                        .getFrom().getLongitude());
                Position to = Position.create(c.getTo().getLatitude(), c.getTo()
                        .getLongitude());
                Position topRight = Position.create(from.getLatitude(),
                        to.getLongitude());
                Position botLeft = Position.create(to.getLatitude(),
                        from.getLongitude());
                double width = from.geodesicDistanceTo(topRight) / 1000;
                double height = from.geodesicDistanceTo(botLeft) / 1000;
                double areaSize = width * height;
                double density = (double) c.getCount() / areaSize;
                c.setDensity(density);
            }
        });
        
        return new VesselClusterJsonRepsonse(requestId, map.values(), inWorld);
        
        
    }

   
    /**
     * Get simple list of anonymous targets that matches the search criteria.
     * 
     * @param searchCriteria
     *            A string that will be matched to all vessel names, IMOs and
     *            MMSIs.
     * @return A list of targets.
     */
    public VesselList searchTargets(String searchCriteria,
            List<AisTarget> targets) {

        VesselList response = new VesselList();

        // Iterate through all vessel targets and add to response
        for (AisTarget target : targets) {
            if (!(target instanceof AisVesselTarget)) {
                continue;
            }

            // TODO: implement sat ttl vs live ttl
            // Determine TTL (could come from configuration)
            // TargetSourceData sourceData = targetEntry.getSourceData();
            // boolean satData = sourceData.isSatData();
            int ttl = conf.getSatTargetTtl();

            // Is it alive
            if (!target.isAlive(ttl)) {
                continue;
            }

            // Maybe filtered away
            if (rejectedBySearchCriteria(target, searchCriteria)) {
                continue;
            }

            response.addTarget((AisVesselTarget) target, target.getMmsi());
        }

        return response;
    }

    /**
     * Returns false if target matches a given searchCriteria. This method only
     * matches on the targets name, mmsi and imo.
     * 
     * @param target
     * @param searchCriteria
     * @return false if the target matches the search criteria.
     * @throws JsonApiException
     */
    public boolean rejectedBySearchCriteria(AisTarget target,
            String searchCriteria) {

        if (!(target instanceof AisVesselTarget)) {
            return true;
        }

        // Get length of search criteria
        int searchLength = searchCriteria.length();

        AisVesselTarget vessel = (AisVesselTarget) target;

        // Get details
        Integer mmsi = vessel.getMmsi();

        // Check mmsi
        String mmsiString = Long.toString(mmsi);
        if (mmsiString.length() >= searchLength
                && mmsiString.substring(0, searchLength).equals(searchCriteria)) {
            return false;
        }

        // Check name
        if (vessel.getVesselStatic() != null
                && vessel.getVesselStatic().getName() != null) {
            String name = vessel.getVesselStatic().getName().toUpperCase();

            // Check entire name
            if (name.length() >= searchLength
                    && name.substring(0, searchLength).equals(
                            searchCriteria.toUpperCase())) {
                return false;
            }

            // Check each word
            String[] words = name.split(" ");
            for (String w : words) {
                if (w.length() >= searchLength
                        && w.substring(0, searchLength).equals(
                                searchCriteria.toUpperCase())) {
                    return false;
                }
            }
        }

        // Check imo - if Class A
        if (vessel instanceof AisClassATarget) {
            AisClassATarget classAVessel = (AisClassATarget) vessel;
            if (classAVessel.getClassAStatic() != null
                    && classAVessel.getClassAStatic().getImoNo() != null) {
                int imo = classAVessel.getClassAStatic().getImoNo();
                String imoString = Integer.toString(imo);
                if (imoString.length() >= searchLength
                        && imoString.substring(0, searchLength).equals(
                                searchCriteria)) {
                    return false;
                }
            }
        }

        return true;
    }

    public AisViewConfiguration getConf() {
        return conf;
    }

    public static BoundingBox getBbox(QueryParams request) {
        // Get corners
        Double topLat = request.getDouble("topLat");
        Double topLon = request.getDouble("topLon");
        Double botLat = request.getDouble("botLat");
        Double botLon = request.getDouble("botLon");

        Position pointA = Position.create(topLat, topLon);
        Position pointB = Position.create(botLat, botLon);
        return BoundingBox.create(pointA, pointB, CoordinateSystem.GEODETIC);
    }

    public BoundingBox tryGetBbox(QueryParams request) {
        try {
            return getBbox(request);
        } catch (NullPointerException e) {
            return null;
        }
    }

    /**
     * Combine two pastTracks (no regard for different pasttrack options or
     * validation of points)
     * 
     * @param p1
     * @param p2
     * @return a new pastTrack
     */
    public IPastTrack combinePastTrack(final IPastTrack p1, final IPastTrack p2) {
        final TreeSet<PastTrackPoint> points = new TreeSet<PastTrackPoint>();

        for (IPastTrack p : Arrays.asList(p1, p2)) {
            if (p != null) {
                List<PastTrackPoint> pps = p.getPoints();
                if (pps != null) {
                    for (PastTrackPoint point : pps) {
                        if (point != null) {
                            points.add(point);
                        }
                    }
                }
            }
        }

        IPastTrack finalPastTrack = new IPastTrack() {
            final TreeSet<PastTrackPoint> inner = points;

            @Override
            public List<PastTrackPoint> getPoints() {
                return Arrays.asList(inner.toArray(new PastTrackPoint[0]));
            }

            @Override
            public void cleanup(int ttl) {
                throw new UnsupportedOperationException(
                        "This Anonymous IPastTrack is immutable");
            }

            @Override
            public void addPosition(AisVesselPosition vesselPosition,
                    int minDist) {
                throw new UnsupportedOperationException(
                        "This Anonymous IPastTrack is immutable");
            }
        };

        return finalPastTrack;
    }

}
