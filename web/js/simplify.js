// Line simplification for the all-tracks overview: radial-distance pre-pass then
// Douglas-Peucker, on flat [lon, lat, lon, lat, …] arrays. Tolerance is in degrees
// (~1e-4 ≈ 10 m of latitude); the overview redraws fine at that fidelity while cutting
// GPS tracks by an order of magnitude.

/** @param {Float64Array|number[]} coords flat lon/lat pairs @returns {Float64Array} */
export function simplify(coords, tolerance) {
  const n = coords.length / 2;
  if (n <= 2) return Float64Array.from(coords);
  const sqTol = tolerance * tolerance;
  const radial = radialPass(coords, sqTol);
  return dpPass(radial, sqTol);
}

function radialPass(coords, sqTol) {
  const out = [coords[0], coords[1]];
  let px = coords[0];
  let py = coords[1];
  for (let i = 2; i < coords.length; i += 2) {
    const x = coords[i];
    const y = coords[i + 1];
    const dx = x - px;
    const dy = y - py;
    if (dx * dx + dy * dy > sqTol) {
      out.push(x, y);
      px = x;
      py = y;
    }
  }
  // Always keep the true endpoint.
  if (out[out.length - 2] !== coords[coords.length - 2] ||
      out[out.length - 1] !== coords[coords.length - 1]) {
    out.push(coords[coords.length - 2], coords[coords.length - 1]);
  }
  return out;
}

function dpPass(coords, sqTol) {
  const n = coords.length / 2;
  const keep = new Uint8Array(n);
  keep[0] = keep[n - 1] = 1;
  // Iterative DP with an explicit stack.
  const stack = [[0, n - 1]];
  while (stack.length) {
    const [first, last] = stack.pop();
    let maxSq = sqTol;
    let index = -1;
    const ax = coords[first * 2];
    const ay = coords[first * 2 + 1];
    const bx = coords[last * 2];
    const by = coords[last * 2 + 1];
    for (let i = first + 1; i < last; i++) {
      const sq = sqSegDist(coords[i * 2], coords[i * 2 + 1], ax, ay, bx, by);
      if (sq > maxSq) {
        index = i;
        maxSq = sq;
      }
    }
    if (index !== -1) {
      keep[index] = 1;
      stack.push([first, index], [index, last]);
    }
  }
  let count = 0;
  for (let i = 0; i < n; i++) if (keep[i]) count++;
  const out = new Float64Array(count * 2);
  let j = 0;
  for (let i = 0; i < n; i++) {
    if (keep[i]) {
      out[j++] = coords[i * 2];
      out[j++] = coords[i * 2 + 1];
    }
  }
  return out;
}

function sqSegDist(px, py, ax, ay, bx, by) {
  let x = ax;
  let y = ay;
  let dx = bx - ax;
  let dy = by - ay;
  if (dx !== 0 || dy !== 0) {
    const t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy);
    if (t > 1) {
      x = bx;
      y = by;
    } else if (t > 0) {
      x += dx * t;
      y += dy * t;
    }
  }
  dx = px - x;
  dy = py - y;
  return dx * dx + dy * dy;
}
