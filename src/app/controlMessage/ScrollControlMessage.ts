import { ControlMessage, ControlMessageInterface } from './ControlMessage';
import Position, { PositionInterface } from '../Position';

export interface ScrollControlMessageInterface extends ControlMessageInterface {
    position: PositionInterface;
    hScroll: number;
    vScroll: number;
    buttons: number;
}

export class ScrollControlMessage extends ControlMessage {
    public static PAYLOAD_LENGTH = 20;

    constructor(readonly position: Position, readonly hScroll: number, readonly vScroll: number, readonly buttons = 0) {
        super(ControlMessage.TYPE_SCROLL);
    }

    /**
     * @override
     */
    public toBuffer(): Buffer {
        const buffer = Buffer.alloc(ScrollControlMessage.PAYLOAD_LENGTH + 1);
        let offset = 0;
        offset = buffer.writeUInt8(this.type, offset);
        offset = buffer.writeUInt32BE(this.position.point.x, offset);
        offset = buffer.writeUInt32BE(this.position.point.y, offset);
        offset = buffer.writeUInt16BE(this.position.screenSize.width, offset);
        offset = buffer.writeUInt16BE(this.position.screenSize.height, offset);
        // scrcpy encodes scroll deltas as signed 16-bit fixed-point values in [-16, 16].
        const encodeScroll = (value: number): number => {
            const normalized = Math.max(-1, Math.min(1, value / 16));
            return Math.round(normalized * 0x7fff);
        };
        offset = buffer.writeInt16BE(encodeScroll(this.hScroll), offset);
        offset = buffer.writeInt16BE(encodeScroll(this.vScroll), offset);
        buffer.writeInt32BE(this.buttons, offset);
        return buffer;
    }

    public toString(): string {
        return `ScrollControlMessage{hScroll=${this.hScroll}, vScroll=${this.vScroll}, position=${this.position}}`;
    }

    public toJSON(): ScrollControlMessageInterface {
        return {
            type: this.type,
            position: this.position.toJSON(),
            hScroll: this.hScroll,
            vScroll: this.vScroll,
            buttons: this.buttons,
        };
    }
}
