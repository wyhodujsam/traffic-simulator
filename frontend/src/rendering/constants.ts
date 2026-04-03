/** Global render scale — multiplies all coordinates and dimensions */
export const RENDER_SCALE = 2;

/** Pixel width per lane — used by frontend projection */
export const LANE_WIDTH_PX = 14 * RENDER_SCALE;

/** Vehicle rendering dimensions */
export const VEHICLE_LENGTH_PX = 8 * RENDER_SCALE;
export const VEHICLE_WIDTH_PX = 5 * RENDER_SCALE;

/** Tick interval in ms (20 Hz) */
export const TICK_INTERVAL_MS = 50;

/** Canvas padding around road network bounds */
export const CANVAS_PADDING = 60 * RENDER_SCALE;

/** Default max speed for color gradient (m/s, ~120 km/h) */
export const DEFAULT_MAX_SPEED = 33.33;

/** Obstacle rendering length along road direction (px) */
export const OBSTACLE_LENGTH_PX = 8 * RENDER_SCALE;

/** Hit-test padding for obstacle click detection (px) */
export const OBSTACLE_HIT_PADDING = 4 * RENDER_SCALE;
