package uk.co.flypig;

/*
 * Created by David Llewellyn-Jones on 2025-02-15.
 *
 * Copyright (C) 2025 David Llewellyn-Jones <david@flypig.co.uk>
 *
 * This is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this code.  If not, see <http://www.gnu.org/licenses/>.
 */

import lombok.Data;
import lombok.NoArgsConstructor;

@com.dslplatform.json.CompiledJson
@Data
@NoArgsConstructor
public final class DownloadLocater {
    public String service;
    public String url;
}

