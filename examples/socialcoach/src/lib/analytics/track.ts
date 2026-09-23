import type {TrackEvent} from "./schema";
/** Disabled in the TBX distribution. No events or device IDs leave the device. */
export function track(_event:TrackEvent):void {}
export function trackOpen(_profile:boolean):void {}
