import request from '../utils/request'
export const getList = () => request.get('/announcement/list')