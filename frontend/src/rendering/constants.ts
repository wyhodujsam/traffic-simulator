/** Pixel width per lane — used by frontend projection */
export const LANE_WIDTH_PX = 14.0;

/** Vehicle rendering dimensions (matches physics model: 4.5m length) */
export const VEHICLE_LENGTH_PX = 5;
export const VEHICLE_WIDTH_PX = 4;

/** Tick interval in ms (20 Hz) */
export const TICK_INTERVAL_MS = 50;

/** Canvas padding around road network bounds */
export const CANVAS_PADDING = 60;

/** Default max speed for color gradient (m/s, ~120 km/h) */
export const DEFAULT_MAX_SPEED = 33.33;

/** Obstacle rendering length along road direction (px) */
export const OBSTACLE_LENGTH_PX = 8;

/** Hit-test padding for obstacle click detection (px) */
export const OBSTACLE_HIT_PADDING = 4;
