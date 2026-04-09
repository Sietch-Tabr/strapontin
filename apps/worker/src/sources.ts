import {
  TicketmasterSource,
  BandsintownSource,
  EventimSource,
} from "@strapontin/sources";

const tmKey = process.env["TICKETMASTER_API_KEY"] ?? "";
const bitId = process.env["BANDSINTOWN_APP_ID"] ?? "strapontin";

export const ticketmaster = new TicketmasterSource(tmKey);
export const bandsintown = new BandsintownSource(bitId);
export const eventim = new EventimSource();
