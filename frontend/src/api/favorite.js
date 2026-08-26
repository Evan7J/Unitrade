import request from '../utils/request'
export const add = (id) => request.post('/favorite/add/' + id)
export const remove = (id) => request.delete('/favorite/remove/' + id)
export const myList = () => request.get('/favorite/my')