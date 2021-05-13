import React from 'react'
import { Rect } from 'react-konva'
import { Tile } from '../../../../shapes'
import { TILE_SIZE_IN_PIXELS } from '../MapConstants'

const RoomTile = ({ tile, color }) => (
    <Rect
        x={tile.positionX * TILE_SIZE_IN_PIXELS}
        y={tile.positionY * TILE_SIZE_IN_PIXELS}
        width={TILE_SIZE_IN_PIXELS}
        height={TILE_SIZE_IN_PIXELS}
        fill={color}
    />
)

RoomTile.propTypes = {
    tile: Tile,
}

export default RoomTile
