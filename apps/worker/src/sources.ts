import { TicketmasterSource, EventimSource } from "@strapontin/sources";

const tmKey = process.env["TICKETMASTER_API_KEY"] ?? "";

export const ticketmaster = new TicketmasterSource(tmKey);
export const eventim = new EventimSource();
