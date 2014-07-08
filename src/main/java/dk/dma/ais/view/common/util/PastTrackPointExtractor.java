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
package dk.dma.ais.view.common.util;

import com.goebl.simplify.PointExtractor;

import dk.dma.ais.data.PastTrackPoint;

/**
 * 
 * @author Jens Tuxen
 *
 */
public final class PastTrackPointExtractor implements PointExtractor<PastTrackPoint>{

    @Override
    public double getX(PastTrackPoint arg0) {
        return arg0.getLat();
    }


    @Override
    public double getY(PastTrackPoint arg0) {
        // TODO Auto-generated method stub
        return arg0.getLon();
    }
}
